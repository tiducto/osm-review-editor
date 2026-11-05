package eu.tiducto.osmreview.shared.presets

object Fixtures {
    val presetsJson = """
    {
      "point": {"fields": ["name"], "geometry": ["vertex", "point"], "tags": {}, "matchScore": 0.1},
      "line": {"fields": ["name"], "geometry": ["line"], "tags": {}, "matchScore": 0.1},
      "area": {"fields": ["name"], "geometry": ["area"], "tags": {"area": "yes"}, "matchScore": 0.1},
      "relation": {"fields": ["name", "relation"], "geometry": ["relation"], "tags": {}},
      "building": {"icon": "maki-home", "fields": ["name", "building"], "geometry": ["area"], "tags": {"building": "*"}, "matchScore": 0.6},
      "building/house": {"icon": "maki-home", "fields": ["name", "building"], "geometry": ["point", "area"], "tags": {"building": "house"}},
      "highway/footway": {"icon": "temaki-pedestrian", "fields": ["name", "surface", "width"], "moreFields": ["lit"], "geometry": ["line"], "tags": {"highway": "footway"}},
      "highway/footway/sidewalk": {"icon": "temaki-pedestrian", "geometry": ["line"], "fields": ["name", "surface", "width"], "moreFields": ["lit", "tactile_paving"], "tags": {"footway": "sidewalk"}, "addTags": {"highway": "footway", "footway": "sidewalk"}},
      "highway/crossing": {"geometry": ["vertex"], "fields": ["crossing"], "tags": {"highway": "crossing"}},
      "highway/crossing/zebra": {"geometry": ["vertex"], "tags": {"highway": "crossing", "crossing": "zebra"}, "searchable": false},
      "highway/bus_stop": {"icon": "temaki-bus", "geometry": ["point", "vertex"], "fields": ["name", "shelter"], "tags": {"highway": "bus_stop"}},
      "amenity/bench": {"geometry": ["point", "vertex", "line"], "fields": ["backrest"], "tags": {"amenity": "bench"}},
      "amenity/bench/wood": {"geometry": ["point"], "tags": {"amenity": "bench", "material": "wood"}},
      "@templates/contact": {"geometry": ["point"], "tags": {"@template": "contact"}, "searchable": false}
    }
    """.trimIndent()

    val fieldsJson = """
    {
      "name": {"key": "name", "type": "localized"},
      "surface": {"key": "surface", "type": "combo", "options": ["asphalt", "paving_stones"]},
      "width": {"key": "width", "type": "number"},
      "lit": {"key": "lit", "type": "check"},
      "tactile_paving": {"key": "tactile_paving", "type": "check"},
      "building": {"key": "building", "type": "combo"},
      "crossing": {"key": "crossing", "type": "combo"},
      "shelter": {"key": "shelter", "type": "check"},
      "backrest": {"key": "backrest", "type": "check"},
      "relation": {"key": "type", "type": "combo"}
    }
    """.trimIndent()

    val enJson = """
    {"en": {"presets": {
      "categories": {},
      "fields": {"surface": {"label": "Surface", "options": {"asphalt": "Asphalt", "paving_stones": "Paving Stones"}}, "width": {"label": "Width (Meters)"}, "lit": {"label": "Lit"}},
      "presets": {
        "point": {"name": "Point"}, "line": {"name": "Line"}, "area": {"name": "Area"}, "relation": {"name": "Relation"},
        "building": {"name": "Building", "terms": ["structure"]},
        "building/house": {"name": "House", "terms": ["home", "family"]},
        "highway/footway": {"name": "Foot Path", "terms": ["hiking", "trail"]},
        "highway/footway/sidewalk": {"name": "Sidewalk", "terms": ["pavement", "sidepath"]},
        "highway/crossing": {"name": "Crossing"},
        "highway/crossing/zebra": {"name": "Marked Crosswalk"},
        "highway/bus_stop": {"name": "Bus Stop", "terms": ["bus", "stop"]},
        "amenity/bench": {"name": "Bench", "terms": ["seat"]},
        "amenity/bench/wood": {"name": "Wooden Bench"}
      }
    }}}
    """.trimIndent()

    val csJson = """
    {"cs": {"presets": {
      "fields": {"surface": {"label": "Povrch", "options": {"paving_stones": "Dlažba"}}},
      "presets": {
        "highway/footway/sidewalk": {"name": "Chodník podél vozovky", "terms": ["chodník", "pro chodce"]},
        "highway/footway": {"name": "Pěšina"},
        "highway/bus_stop": {"name": "Autobusová zastávka", "aliases": ["Zastávka busu"]},
        "amenity/bench": {"name": "Lavička"}
      }
    }}}
    """.trimIndent()

    fun index(user: List<UserPreset> = emptyList()): PresetIndex = PresetIndex(
        IdTaggingSchema.presets(presetsJson),
        IdTaggingSchema.fields(fieldsJson),
        listOf(IdTaggingSchema.translations(enJson), IdTaggingSchema.translations(csJson)),
        user,
    )

    val sidewalkCz = """
    { "id":"custom/sidewalk-cz", "extends":"highway/footway/sidewalk", "name":"Chodník", "apply":"merge",
      "addTags":{"highway":"footway","footway":"sidewalk","surface":"paving_stones"},
      "fields":["{highway/footway/sidewalk}","width","lit"] }
    """.trimIndent()

    val smartCrossing = """
    { "id":"smart/footway-crossing", "name":"Footway → crossing", "geometry":["line"],
      "match":"way[highway=footway][!footway]",
      "params":{"crossing":{"type":"combo","options":["uncontrolled","traffic_signals","unmarked"],"default":"uncontrolled"}},
      "steps":[
        {"op":"findCrossing","with":"way[highway][highway!~/^(footway|path|steps|cycleway|pedestrian)$/]","as":"road","require":"one"},
        {"op":"ensureSharedNode","ways":["self","road"],"as":"x"},
        {"op":"splitAround","way":"self","node":"x","at":"nearestNodeOr","distance":"half_width(road)","as":"piece"},
        {"op":"tag","target":"piece","set":{"footway":"crossing","crossing":"{crossing}"}},
        {"op":"tag","target":"x","set":{"highway":"crossing","crossing":"{crossing}"}} ] }
    """.trimIndent()
}
