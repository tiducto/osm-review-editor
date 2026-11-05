package eu.tiducto.osmreview.server.tiles

object TileContent {
    const val MIN_ZOOM = 9
    const val DETAIL_ZOOM = 15
    const val MAX_ZOOM = 16
    const val MAX_BAND_ZOOM = 20
    const val BAND_PX = 30.0
    const val TILE_PX = 512.0
    const val SCHEMA = 3

    fun extent(z: Int): Int = if (z >= DETAIL_ZOOM) 8192 else 4096

    fun bufferPx(z: Int): Double = if (z >= DETAIL_ZOOM) 16.0 else 8.0

    private val MAJOR_ROADS = "motorway,motorway_link,trunk,trunk_link,primary,primary_link"
    private val MID_ROADS = "$MAJOR_ROADS,secondary,secondary_link,tertiary,tertiary_link"
    private val MINOR_ROADS = "$MID_ROADS,unclassified,residential,living_street,pedestrian,service,track"

    private val POINT_KEYS = listOf(
        "place", "amenity", "shop", "tourism", "leisure", "historic", "craft", "office", "healthcare",
        "public_transport", "railway", "aeroway", "man_made", "emergency", "sport", "club", "power",
        "highway=bus_stop", "natural=tree,peak,spring,cave_entrance",
    )

    fun query(z: Int): String? = when {
        z >= DETAIL_ZOOM -> null
        z == 14 -> listOf(
            "w[highway]", "w[railway]", "w[waterway]", "w[barrier]", "w[power=line]", "w[aerialway]", "a",
        ).plus(POINT_KEYS.map { "n[$it]" }).joinToString(", ")
        z == 13 -> "w[highway], w[railway], w[waterway], a, n[place]"
        z == 12 -> "w[highway=$MINOR_ROADS], w[railway=rail,light_rail,subway,tram,narrow_gauge,funicular], " +
            "w[waterway=river,canal,stream], a[landuse], a[natural], a[leisure], a[amenity], a[aeroway], " +
            "a[waterway], a[military], n[place=city,town,village,suburb,quarter]"
        z == 11 -> "w[highway=$MID_ROADS], w[railway=rail,light_rail,subway,narrow_gauge], w[waterway=river,canal], " +
            "a[landuse], a[natural], a[leisure], a[amenity], a[aeroway], a[waterway], n[place=city,town,village,suburb]"
        else -> "w[highway=$MAJOR_ROADS], w[railway=rail], w[waterway=river,canal], a[natural=water], " +
            "a[landuse=forest], a[natural=wood], a[waterway=riverbank], n[place=city,town]"
    }

    fun allTags(z: Int): Boolean = z >= DETAIL_ZOOM

    fun includeVertices(z: Int): Boolean = z >= DETAIL_ZOOM

    fun includeBuildings(z: Int): Boolean = z >= 13

    fun minAreaPx(z: Int): Double = when {
        z >= 13 -> 0.0
        z >= 11 -> 4.0
        else -> 16.0
    }

    val CURATED_KEYS: Set<String> = setOf(
        "name", "ref", "place", "highway", "railway", "waterway", "landuse", "natural", "leisure", "amenity",
        "building", "building:part", "shop", "tourism", "historic", "craft", "office", "aeroway", "man_made",
        "power", "aerialway", "barrier", "military", "emergency", "healthcare", "public_transport", "sport",
        "club", "playground", "religion", "service", "tracktype", "surface", "bridge", "tunnel", "layer",
        "location", "oneway", "junction", "footway", "cycleway", "crossing", "crossing:markings", "foot",
        "bicycle", "construction", "parking", "area", "type", "water", "wetland", "usage", "status", "intermittent",
        "disused:railway", "abandoned:railway", "construction:railway", "proposed:railway", "wikidata",
        "brand:wikidata", "information", "vending", "cuisine", "station", "train", "bus", "tram", "subway",
    )

    val LABEL_KEYS: Set<String> = setOf(
        "amenity", "shop", "tourism", "leisure", "historic", "craft", "office", "aeroway", "man_made", "power",
        "emergency", "healthcare", "public_transport", "railway", "sport", "club", "playground", "place",
        "military", "attraction", "waterway",
    )

    val NON_AREA_RELATION_TYPES: Set<String> = setOf("boundary", "route", "route_master", "network", "site")
}
