package eu.tiducto.osmreview.shared.presets

import eu.tiducto.osmreview.shared.TagEdit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresetIndexTest {
    private val index = Fixtures.index()
    private val sidewalkCz = PresetFiles.parse("presets/sidewalk-cz.json", Fixtures.sidewalkCz).presets.single().preset
    private val withUser = index.withUser(listOf(UserPreset(sidewalkCz, "presets/sidewalk-cz.json")))

    private fun best(tags: Map<String, String>, g: PresetGeometry, idx: PresetIndex = index) = idx.match(tags, g)!!

    @Test
    fun matchesTheMostSpecificPreset() {
        assertEquals("highway/footway/sidewalk", best(mapOf("highway" to "footway", "footway" to "sidewalk"), PresetGeometry.LINE).preset.id)
        assertEquals("highway/footway", best(mapOf("highway" to "footway"), PresetGeometry.LINE).preset.id)
        assertEquals("amenity/bench/wood", best(mapOf("amenity" to "bench", "material" to "wood"), PresetGeometry.POINT).preset.id)
        assertEquals("amenity/bench", best(mapOf("amenity" to "bench", "material" to "wood"), PresetGeometry.LINE).preset.id)
    }

    @Test
    fun wildcardScoresHalfAndMatchScoreWeights() {
        val house = best(mapOf("building" to "house"), PresetGeometry.AREA)
        assertEquals("building/house", house.preset.id)
        assertEquals(1.0, house.score)
        val generic = best(mapOf("building" to "garage"), PresetGeometry.AREA)
        assertEquals("building", generic.preset.id)
        assertEquals(0.3, generic.score)
        assertEquals(-1.0, index.score("building", mapOf("amenity" to "bench")))
    }

    @Test
    fun geometryAndFallbacks() {
        assertEquals("highway/crossing/zebra", best(mapOf("highway" to "crossing", "crossing" to "zebra"), PresetGeometry.VERTEX).preset.id)
        assertEquals("point", best(mapOf("highway" to "crossing"), PresetGeometry.POINT).preset.id)
        assertEquals("line", best(mapOf("foo" to "bar"), PresetGeometry.LINE).preset.id)
        val area = best(mapOf("foo" to "bar"), PresetGeometry.AREA)
        assertEquals("area", area.preset.id)
        assertEquals(0.0, area.score)
        assertEquals("relation", best(mapOf("type" to "route"), PresetGeometry.RELATION).preset.id)
    }

    @Test
    fun unsearchablePresetsLoseTies() {
        assertTrue(index.score("highway/crossing/zebra", mapOf("highway" to "crossing", "crossing" to "zebra")) < 2.0)
    }

    @Test
    fun pseudoTagsAreIgnored() {
        assertEquals("highway/footway", best(mapOf("highway" to "footway", "geodesk:missing_members" to "yes"), PresetGeometry.LINE).preset.id)
    }

    @Test
    fun resolvesUserOverlay() {
        val r = assertNotNull(withUser.resolve("custom/sidewalk-cz", "cs"))
        assertEquals("Chodník", r.name)
        assertEquals(PresetOrigin.USER, r.origin)
        assertEquals("presets/sidewalk-cz.json", r.file)
        assertEquals(listOf(PresetGeometry.LINE), r.geometry)
        assertEquals(mapOf("footway" to "sidewalk"), r.tags)
        assertEquals(mapOf("highway" to "footway", "footway" to "sidewalk", "surface" to "paving_stones"), r.addTags)
        assertEquals(r.addTags, r.removeTags)
        assertEquals(listOf("name", "surface", "width", "lit"), r.fields)
        assertEquals(listOf("lit", "tactile_paving"), r.moreFields)
        assertEquals("temaki-pedestrian", r.icon)
        assertEquals(listOf("chodník", "pro chodce"), r.terms)
        assertEquals("Povrch", r.fieldInfo.first { it.id == "surface" }.label)
        assertEquals("Dlažba", r.fieldInfo.first { it.id == "surface" }.optionLabels["paving_stones"])
        assertEquals("Asphalt", r.fieldInfo.first { it.id == "surface" }.optionLabels["asphalt"])
    }

    @Test
    fun userOverlayWinsTiesAndAddTagsBoost() {
        val tags = mapOf("highway" to "footway", "footway" to "sidewalk", "surface" to "paving_stones")
        val m = best(tags, PresetGeometry.LINE, withUser)
        assertEquals("custom/sidewalk-cz", m.preset.id)
        assertEquals(3.0, m.score)
        assertEquals("custom/sidewalk-cz", best(mapOf("highway" to "footway", "footway" to "sidewalk"), PresetGeometry.LINE, withUser).preset.id)
    }

    @Test
    fun nameReferencesAndSelfExtension() {
        val renamed = Preset(id = "highway/bus_stop", extends = "highway/bus_stop", name = "Zastávka", addTags = mapOf("highway" to "bus_stop", "bus" to "yes"))
        val alias = Preset(id = "custom/stop", extends = "highway/bus_stop", name = "{highway/bus_stop}", fields = listOf("{highway/bus_stop}", "lit"))
        val idx = index.withUser(listOf(UserPreset(renamed), UserPreset(alias)))
        val r = idx.resolve("highway/bus_stop", "cs")!!
        assertEquals("Zastávka", r.name)
        assertEquals(PresetOrigin.USER, r.origin)
        assertEquals(listOf("name", "shelter"), r.fields)
        assertEquals("Zastávka", idx.resolve("custom/stop", "cs")!!.name)
        assertEquals(listOf("name", "shelter", "lit"), idx.resolve("custom/stop")!!.fields)
    }

    @Test
    fun inheritsFieldsFromParentPathLikeId() {
        val r = index.resolve("highway/crossing/zebra")!!
        assertEquals(emptyList(), r.fields)
        val wood = index.resolve("amenity/bench/wood")!!
        assertEquals(listOf("backrest"), wood.fields)
    }

    @Test
    fun detectsCycles() {
        val a = Preset(id = "custom/a", extends = "custom/b")
        val b = Preset(id = "custom/b", extends = "custom/a")
        val idx = index.withUser(listOf(UserPreset(a), UserPreset(b)))
        assertFailsWith<PresetCycleException> { idx.resolve("custom/a") }
        assertNotNull(idx.resolveError("custom/b"))
    }

    @Test
    fun localisesWithFallbackToEnglish() {
        assertEquals("Lavička", index.resolve("amenity/bench", "cs-CZ")!!.name)
        assertEquals("Wooden Bench", index.resolve("amenity/bench/wood", "cs")!!.name)
        assertEquals("Bench", index.resolve("amenity/bench", "de")!!.name)
        assertEquals("en", index.resolve("amenity/bench", "de")!!.locale)
    }

    @Test
    fun searchByNameTermsAliasesWithoutDiacritics() {
        assertEquals("highway/footway/sidewalk", index.search("chodnik", "cs").first().id)
        assertEquals("highway/bus_stop", index.search("zastavka busu", "cs").first().id)
        assertEquals("highway/footway/sidewalk", index.search("pavement", "en").first().id)
        assertEquals("highway/footway/sidewalk", index.search("sidewalk", "cs").first().id)
        assertTrue(index.search("crosswalk").none { it.id == "highway/crossing/zebra" })
        assertTrue(index.search("contact").isEmpty())
        assertEquals(listOf("building/house"), index.search("house", geometry = PresetGeometry.POINT).map { it.id })
        assertEquals("custom/sidewalk-cz", withUser.search("chodnik", "cs").first().id)
    }

    @Test
    fun applyMergeKeepsOtherTagsAndRemovesFromPresetTags() {
        val r = withUser.apply("custom/sidewalk-cz", mapOf("highway" to "footway", "name" to "A", "lit" to "yes"), PresetGeometry.LINE)
        assertEquals(TagEdit(set = mapOf("footway" to "sidewalk", "surface" to "paving_stones")), r.edit)
        assertEquals(mapOf("footway" to "sidewalk", "highway" to "footway", "lit" to "yes", "name" to "A", "surface" to "paving_stones"), r.tags)

        val switched = index.apply("amenity/bench", mapOf("highway" to "bus_stop", "name" to "X", "bench" to "yes"), fromPresetId = "highway/bus_stop")
        assertEquals(mapOf("amenity" to "bench", "bench" to "yes", "name" to "X"), switched.tags)
        assertEquals(listOf("highway"), switched.edit.remove)
    }

    @Test
    fun applyWildcardKeepsExistingValue() {
        assertEquals("garage", index.apply("building", mapOf("building" to "garage")).tags["building"])
        assertEquals("yes", index.apply("building", emptyMap()).tags["building"])
    }

    @Test
    fun applyReplaceAndCreate() {
        val replace = Preset(id = "custom/r", extends = "highway/footway", apply = PresetApplyMode.REPLACE)
        val create = Preset(id = "custom/c", extends = "amenity/bench", apply = PresetApplyMode.CREATE, addTags = mapOf("amenity" to "bench", "backrest" to "yes"))
        val idx = index.withUser(listOf(UserPreset(replace), UserPreset(create)))
        val r = idx.apply("custom/r", mapOf("highway" to "path", "name" to "x"))
        assertEquals(mapOf("highway" to "footway"), r.edit.replaceAll)
        assertEquals(mapOf("highway" to "footway"), r.tags)
        assertEquals(mapOf("amenity" to "bench", "backrest" to "yes"), idx.apply("custom/c", emptyMap()).tags)
        assertFailsWith<IllegalArgumentException> { idx.apply("custom/c", mapOf("amenity" to "bench")) }
        assertFailsWith<IllegalArgumentException> { idx.apply("highway/footway", emptyMap(), PresetGeometry.POINT) }
    }

    @Test
    fun overlayAndDetach() {
        assertEquals(Preset(id = "custom/highway/footway", extends = "highway/footway", apply = PresetApplyMode.MERGE), index.overlay("highway/footway"))
        val d = withUser.detach("custom/sidewalk-cz")
        assertNull(d.extends)
        assertEquals("Chodník", d.name)
        assertEquals(mapOf("footway" to "sidewalk"), d.tags)
        assertEquals(listOf("name", "surface", "width", "lit"), d.fields)
        assertEquals(listOf(PresetGeometry.LINE), d.geometry)
        assertEquals("temaki-pedestrian", d.icon)
        val again = index.withUser(listOf(UserPreset(d, "presets/sidewalk-cz.json")))
        assertEquals(withUser.resolve("custom/sidewalk-cz")!!.copy(extends = null, fieldInfo = emptyList(), terms = emptyList()),
            again.resolve("custom/sidewalk-cz")!!.copy(fieldInfo = emptyList(), terms = emptyList()))
    }
}
