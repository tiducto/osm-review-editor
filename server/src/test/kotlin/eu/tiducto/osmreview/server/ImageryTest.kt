package eu.tiducto.osmreview.server

import eu.tiducto.osmreview.server.db.Db
import eu.tiducto.osmreview.server.db.SettingsRepo
import eu.tiducto.osmreview.shared.ImagerySource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageryTest {
    private val tms = ImagerySource(
        id = "test-tms", name = "Test", type = "tms", url = "https://tiles.example.org/{z}/{x}/{y}.png",
        attribution = "© Example", sourceTag = "Example imagery",
    )

    @Test
    fun defaultsAreValid() {
        assertEquals(ImageryStore.DEFAULTS, ImageryStore.validate(ImageryStore.DEFAULTS))
        assertTrue(ImageryStore.DEFAULTS.filter { it.id != "osm-standard" }.all { it.sourceTag.isNotBlank() })
        assertEquals("ČÚZK ortofoto", ImageryStore.DEFAULTS.first { it.id == "cuzk-ortofoto" }.sourceTag)
    }

    @Test
    fun defaultsPutTheOrthophotoFirstAndTheCadastreAsAnOverlay() {
        assertEquals(ImageryStore.DEFAULT_BACKGROUND, ImageryStore.DEFAULTS.first().id)
        assertEquals("tms", ImageryStore.DEFAULTS.first().type)
        val osm = ImageryStore.DEFAULTS.first { it.id == "osm-standard" }
        assertEquals("https://tile.openstreetmap.org/{z}/{x}/{y}.png", osm.url)
        assertEquals(19, osm.maxZoom)
        assertEquals("© OpenStreetMap contributors", osm.attribution)
        assertEquals(listOf("cuzk-km"), ImageryStore.DEFAULTS.filter { it.overlay }.map { it.id })
    }

    @Test
    fun upgradeMovesOldListsToTheNewDefaults() {
        val old = listOf(
            ImageryStore.ORTHO_WMS.copy(id = "cuzk-ortofoto", name = "ČÚZK orthophoto"),
            ImageryStore.CADASTRE.copy(name = "ČÚZK cadastral map (overlay)", overlay = false),
            ImageryStore.ESRI,
        )
        val up = ImageryStore.upgrade(old)
        assertEquals(listOf("cuzk-ortofoto", "osm-standard", "cuzk-km", "esri-world-imagery", "cuzk-ortofoto-wms"), up.map { it.id })
        assertEquals(ImageryStore.ORTHO, up.first { it.id == "cuzk-ortofoto" })
        assertTrue(up.first { it.id == "cuzk-km" }.overlay)
        val custom = listOf(tms)
        assertEquals(listOf(ImageryStore.OSM_STANDARD, tms), ImageryStore.upgrade(custom))
        assertEquals(ImageryStore.DEFAULTS.toSet(), ImageryStore.upgrade(ImageryStore.DEFAULTS).toSet())
    }

    @Test
    fun validationTrimsAndNormalises() {
        val out = ImageryStore.validate(listOf(tms.copy(id = " test-tms ", type = "TMS", attribution = " © Example ")))
        assertEquals(listOf(tms), out)
    }

    @Test
    fun validationRejectsBadSources() {
        fun rejects(vararg list: ImagerySource, expect: String) {
            val e = assertFailsWith<IllegalArgumentException> { ImageryStore.validate(list.toList()) }
            assertTrue(expect in e.message!!, "expected '$expect' in '${e.message}'")
        }
        rejects(tms, tms.copy(name = "Other"), expect = "duplicate id 'test-tms'")
        rejects(tms.copy(url = "ftp://tiles.example.org/{z}/{x}/{y}.png"), expect = "http(s)")
        rejects(tms.copy(url = "tiles.example.org/{z}/{x}/{y}.png"), expect = "http(s)")
        rejects(tms.copy(url = "javascript:alert(1)"), expect = "http(s)")
        rejects(tms.copy(attribution = "  "), expect = "attribution is required")
        rejects(tms.copy(name = ""), expect = "name is required")
        rejects(tms.copy(id = "Bad Id"), expect = "id must be")
        rejects(tms.copy(type = "xyz"), expect = "type must be")
        rejects(tms.copy(url = "https://tiles.example.org/tile.png"), expect = "{z}, {x} and {y}")
        rejects(tms.copy(type = "wms", url = "https://wms.example.org/wms?LAYERS=a"), expect = "{bbox-epsg-3857}")
        rejects(tms.copy(maxZoom = 30), expect = "maxZoom")
        assertEquals(1, ImageryStore.validate(listOf(tms.copy(url = "https://ecn.t0.tiles.virtualearth.net/tiles/a{quadkey}.jpeg?g=1"))).size)
        assertEquals(0, ImageryStore.validate(emptyList()).size)
    }

    @Test
    fun bingMetadataBecomesATmsSource() {
        val src = BingImagery.fromMetadata(BING_METADATA)
        assertEquals("bing", src.id)
        assertEquals("https://ecn.t0.tiles.virtualearth.net/tiles/a{quadkey}.jpeg?g=14837&n=z", src.url)
        assertEquals(21, src.maxZoom)
        assertEquals("Bing", src.sourceTag)
        assertTrue("Microsoft" in src.attribution)
        ImageryStore.validate(listOf(src))
        assertFailsWith<IllegalArgumentException> { BingImagery.fromMetadata("""{"resourceSets":[]}""") }
    }

    @Test
    fun bingResolveCallsMetadataApi() = runBlocking {
        var seen: String? = null
        val http = HttpClient(
            MockEngine { req ->
                seen = req.url.toString()
                respond(BING_METADATA, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            },
        )
        val src = BingImagery.resolve(http, "secret")
        assertTrue(seen!!.startsWith(BingImagery.METADATA_URL) && "key=secret" in seen!! && "uriScheme=https" in seen!!)
        assertEquals("bing", src.id)
        val denied = HttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) })
        assertFailsWith<IllegalArgumentException> { BingImagery.resolve(denied, "bad") }
        Unit
    }

    @Test
    fun settingsRoundTrip() {
        if (!runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)) return
        PostgreSQLContainer("postgres:18").use { pg ->
            pg.start()
            Db(pg.jdbcUrl, pg.username, pg.password).use { db ->
                val settings = SettingsRepo(db)
                val store = ImageryStore(settings)
                assertNull(settings.get(ImageryStore.KEY))
                settings.put(ImageryStore.KEY, kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ImagerySource.serializer()), listOf(ImageryStore.ESRI)))
                assertEquals(listOf("osm-standard", "esri-world-imagery"), store.configured().map { it.id })
                assertEquals("2", settings.get(ImageryStore.VERSION_KEY))
                assertEquals(listOf("osm-standard", "esri-world-imagery"), store.configured().map { it.id })
                assertEquals(ImageryStore.DEFAULTS, store.reset())
                assertEquals(ImageryStore.DEFAULTS, store.configured())
                assertTrue(settings.get(ImageryStore.KEY)!!.contains("ČÚZK ortofoto"))

                val custom = listOf(tms, ImageryStore.DEFAULTS[2])
                assertEquals(custom, store.replace(custom))
                assertEquals(custom, ImageryStore(settings).configured())

                assertFailsWith<IllegalArgumentException> { store.replace(listOf(tms, tms)) }
                assertEquals(custom, store.configured())

                store.bing = BingImagery.fromMetadata(BING_METADATA)
                assertEquals(custom.map { it.id } + "bing", store.all().map { it.id })
                assertEquals(custom, store.configured())

                assertEquals(ImageryStore.DEFAULTS, store.reset())
                assertEquals(ImageryStore.DEFAULTS, ImageryStore(settings).configured())

                settings.put(ImageryStore.KEY, "not json")
                assertEquals(ImageryStore.DEFAULTS, store.configured())
            }
        }
    }

    private companion object {
        val BING_METADATA = """
            {"authenticationResultCode":"ValidCredentials","brandLogoUri":"https://dev.virtualearth.net/Branding/logo_powered_by.png",
             "resourceSets":[{"estimatedTotal":1,"resources":[{"__type":"ImageryMetadata:http://schemas.microsoft.com/search/local/ws/rest/v1",
             "imageHeight":256,"imageWidth":256,"imageUrl":"https://ecn.{subdomain}.tiles.virtualearth.net/tiles/a{quadkey}.jpeg?g=14837&n=z",
             "imageUrlSubdomains":["t0","t1","t2","t3"],"vintageEnd":null,"vintageStart":null,"zoomMax":21,"zoomMin":1}]}],
             "statusCode":200,"statusDescription":"OK"}
        """.trimIndent()
    }
}
