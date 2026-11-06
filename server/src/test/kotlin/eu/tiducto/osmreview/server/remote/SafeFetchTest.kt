package eu.tiducto.osmreview.server.remote

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class SafeFetchTest {
    private val public = InetAddress.getByAddress("osm.test", byteArrayOf(93.toByte(), 184.toByte(), 215.toByte(), 14))
    private val noDns: (String) -> List<InetAddress> = { error("no DNS lookups expected for $it") }

    private fun refused(url: String, resolver: (String) -> List<InetAddress> = noDns): String {
        val e = assertFailsWith<RefusedTargetException>(url) { SafeFetcher("test", resolver).target(url) }
        return e.message!!
    }

    @Test
    fun onlyHttpAndHttpsAreAccepted() {
        for (url in listOf("file:///etc/passwd", "ftp://example.org/a.osm", "gopher://example.org/", "jar:http://example.org/a.jar!/", "/relative.osm", "data:text/xml,<osm/>")) {
            val message = refused(url)
            assertTrue("http and https" in message || "Invalid URL" in message, "$url: $message")
        }
        assertTrue("user name" in refused("http://user:secret@93.184.215.14/a.osm"))
        assertTrue("user name" in refused("http://evil.example@127.0.0.1/a.osm"))
    }

    @Test
    fun loopbackPrivateAndSpecialAddressesAreRefusedInEveryNotation() {
        val urls = listOf(
            "http://127.0.0.1/", "http://127.0.0.1:3000/api/drafts", "http://127.1/", "http://127.0.1/", "http://2130706433/",
            "http://0177.0.0.1/", "http://0x7f.0.0.1/", "http://0x7f000001/", "http://017700000001/", "http://127.0.0.1./",
            "http://0/", "http://0.0.0.0/", "http://10.0.0.1/", "http://172.16.5.4/", "http://172.31.255.255/", "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data/", "http://100.64.0.1/", "http://224.0.0.1/", "http://239.255.255.250/",
            "http://255.255.255.255/", "http://240.0.0.1/",
            "http://[::1]/", "http://[::]/", "http://[0:0:0:0:0:0:0:1]/", "http://[::ffff:127.0.0.1]/", "http://[::ffff:7f00:1]/",
            "http://[::ffff:10.0.0.1]/", "http://[::ffff:169.254.169.254]/", "http://[::127.0.0.1]/", "http://[fe80::1]/",
            "http://[fc00::1]/", "http://[fd12:3456:789a::1]/", "http://[ff02::1]/", "http://[64:ff9b::7f00:1]/",
            "http://[2002:7f00:1::1]/", "http://[2001:0::1]/", "http://[fec0::1]/",
            "https://127.0.0.1:443/", "http://localhost/", "http://localhost./", "http://LOCALHOST:8111/", "http://api.localhost/",
        )
        for (url in urls) {
            val message = refused(url)
            assertTrue(message.startsWith("Refusing") || message.startsWith("Invalid") || message.contains("no valid host"), "$url: $message")
        }
        assertTrue("loopback" in refused("http://2130706433/"))
        assertTrue("loopback" in refused("http://0177.0.0.1/"))
        assertTrue("loopback" in refused("http://[::ffff:127.0.0.1]/"))
        assertTrue("loopback" in refused("http://[::ffff:7f00:1]/"))
        assertTrue("link-local" in refused("http://169.254.169.254/"))
        assertTrue("unique-local" in refused("http://[fd00::1]/"))
        assertTrue("this computer" in refused("http://localhost./"))
    }

    @Test
    fun malformedNumericHostsAreRefused() {
        for (url in listOf("http://256.0.0.1/", "http://1.2.3.4.5/", "http://08.0.0.1/", "http://0x100000000/", "http://example.123/", "http://[::1%25lo0]/")) {
            refused(url)
        }
    }

    @Test
    fun ipv4LiteralsFollowTheUrlStandard() {
        fun v4(s: String) = AddressPolicy.ipv4Literal(s)?.joinToString(".") { (it.toInt() and 0xff).toString() }
        assertEquals("127.0.0.1", v4("2130706433"))
        assertEquals("127.0.0.1", v4("0177.0.0.1"))
        assertEquals("127.0.0.1", v4("0x7f.1"))
        assertEquals("127.0.0.1", v4("127.0.0.1."))
        assertEquals("10.0.0.255", v4("10.255"))
        assertEquals("93.184.215.14", v4("93.184.215.14"))
        assertNull(v4("256.1.1.1"))
        assertNull(v4("1.2.3.4.5"))
        assertNull(v4("4294967296"))
    }

    @Test
    fun resolvedNamesAreCheckedAndEveryAddressMustBeAllowed() {
        val rebinding: (String) -> List<InetAddress> = { listOf(public, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) }
        assertTrue("127.0.0.1 is a loopback" in refused("http://mixed.test/", rebinding))
        assertTrue("private" in refused("http://internal.test/", { listOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 0, 1))) }))
        val t = SafeFetcher("test", { listOf(public) }).target("https://osm.test/x.osm?bbox=1")
        assertEquals(public, t.address)
        assertEquals(443, t.port)
        assertEquals("osm.test", t.host)
        assertEquals(public.hostAddress, SafeFetcher("test", noDns).target("http://93.184.215.14:8080/").address.hostAddress)
        assertEquals(8080, SafeFetcher("test", noDns).target("http://93.184.215.14:8080/").port)
    }

    private lateinit var server: HttpServer
    private val hits = CopyOnWriteArrayList<String>()
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val trusted = InetAddress.getByAddress("osm.test", byteArrayOf(127, 0, 0, 1))
    private var lookups = 0

    private val port get() = server.address.port

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        server.executor = Executors.newCachedThreadPool()
        fun handle(path: String, h: (HttpExchange) -> Unit) = server.createContext(path) { ex ->
            hits += ex.requestURI.toString()
            ex.use(h)
        }
        handle("/data.osm") { ex ->
            val body = """<osm version="0.6"><node id="-1" lat="1" lon="2"><tag k="amenity" v="bench"/></node></osm>""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/xml; charset=utf-8")
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.write(body)
        }
        handle("/gzip.osm") { ex ->
            val buf = ByteArrayOutputStream()
            GZIPOutputStream(buf).use { it.write("<osm version=\"0.6\"/>".toByteArray()) }
            ex.responseHeaders.add("Content-Encoding", "gzip")
            ex.sendResponseHeaders(200, buf.size().toLong())
            ex.responseBody.write(buf.toByteArray())
        }
        handle("/chunked.osm") { ex ->
            ex.sendResponseHeaders(200, 0)
            repeat(3) { ex.responseBody.write("<!-- part $it -->".toByteArray()); ex.responseBody.flush() }
        }
        handle("/big.osm") { ex ->
            ex.sendResponseHeaders(200, 0)
            val chunk = ByteArray(4096) { 'x'.code.toByte() }
            runCatching { repeat(100) { ex.responseBody.write(chunk) } }
        }
        handle("/declared-big.osm") { ex ->
            ex.sendResponseHeaders(200, 1_000_000)
            runCatching { ex.responseBody.write(ByteArray(1_000_000)) }
        }
        handle("/slow.osm") { ex ->
            Thread.sleep(1500)
            runCatching { ex.sendResponseHeaders(200, -1) }
        }
        handle("/to-loopback") { ex -> redirect(ex, "http://127.0.0.1:$port/data.osm") }
        handle("/to-localhost") { ex -> redirect(ex, "http://localhost.:$port/data.osm") }
        handle("/to-mapped") { ex -> redirect(ex, "http://[::ffff:127.0.0.1]:$port/data.osm") }
        handle("/to-metadata") { ex -> redirect(ex, "http://169.254.169.254/latest/meta-data/") }
        handle("/to-file") { ex -> redirect(ex, "file:///etc/passwd") }
        handle("/to-relative") { ex -> redirect(ex, "/data.osm") }
        handle("/loop") { ex -> redirect(ex, "/loop") }
        handle("/missing") { ex -> ex.sendResponseHeaders(404, -1) }
        server.start()
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun redirect(ex: HttpExchange, to: String) {
        ex.responseHeaders.add("Location", to)
        ex.sendResponseHeaders(302, -1)
    }

    private fun fetcher(maxBytes: Long = 64 * 1024) = SafeFetcher(
        "test",
        resolver = { name ->
            lookups++
            if (name == "osm.test") listOf(trusted) else InetAddress.getAllByName(name).toList()
        },
        allowed = { it === trusted || AddressPolicy.isPublic(it) },
        maxBytes = maxBytes,
        timeout = 800.milliseconds,
        maxRedirects = 3,
    )

    private fun url(path: String) = "http://osm.test:$port$path"

    @Test
    fun fetchesFromTheValidatedAddress() = runBlocking {
        val body = fetcher().fetch(url("/data.osm?x=1"))
        assertTrue("amenity" in body, body)
        assertEquals(listOf("/data.osm?x=1"), hits)
        assertEquals(1, lookups)
        assertEquals("<osm version=\"0.6\"/>", fetcher().fetch(url("/gzip.osm")))
        assertEquals("<!-- part 0 --><!-- part 1 --><!-- part 2 -->", fetcher().fetch(url("/chunked.osm")))
    }

    @Test
    fun redirectsAreRevalidatedOnEveryHop() = runBlocking {
        for (path in listOf("/to-loopback", "/to-localhost", "/to-mapped", "/to-metadata", "/to-file")) {
            hits.clear()
            assertFailsWith<RefusedTargetException>(path) { fetcher().fetch(url(path)) }
            assertEquals(listOf(path), hits, "$path must not reach the redirect target")
        }
        hits.clear()
        assertTrue("amenity" in fetcher().fetch(url("/to-relative")))
        assertEquals(listOf("/to-relative", "/data.osm"), hits)
        val loop = assertFailsWith<RefusedTargetException> { fetcher().fetch(url("/loop")) }
        assertTrue("Too many redirects" in loop.message!!)
    }

    @Test
    fun sizeAndTimeAreCapped() = runBlocking {
        val big = assertFailsWith<RefusedTargetException> { fetcher(maxBytes = 100_000).fetch(url("/big.osm")) }
        assertTrue("larger than" in big.message!!)
        assertFailsWith<RefusedTargetException> { fetcher(maxBytes = 100_000).fetch(url("/declared-big.osm")) }
        val slow = assertFailsWith<RemoteFetchException> { fetcher().fetch(url("/slow.osm")) }
        assertTrue("timed out" in slow.message!! || "longer than" in slow.message!!, slow.message)
        val missing = assertFailsWith<RemoteFetchException> { fetcher().fetch(url("/missing")) }
        assertTrue("HTTP 404" in missing.message!!)
    }

    @Test
    fun theRealPolicyRefusesTheLocalTestServer() = runBlocking {
        assertFailsWith<RefusedTargetException> { SafeFetcher("test").fetch("http://127.0.0.1:$port/data.osm") }
        assertFailsWith<RefusedTargetException> { SafeFetcher("test").fetch("http://localhost:$port/data.osm") }
        assertTrue(hits.isEmpty())
    }
}
