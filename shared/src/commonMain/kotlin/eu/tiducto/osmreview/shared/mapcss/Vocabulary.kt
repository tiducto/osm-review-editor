package eu.tiducto.osmreview.shared.mapcss

internal object Vocabulary {
    val STYLE = setOf(
        "color", "width", "dashes", "opacity", "casing-color", "casing-width", "fill-color", "fill-opacity",
        "text", "z-index",
    )

    const val DIM_OTHERS = "-ore-dim-others"
    const val LEGEND = "-ore-legend"
    const val STATS = "-ore-stats"

    val EXTENSIONS = setOf(DIM_OTHERS, LEGEND, STATS)

    val STATS_VALUES = setOf("length", "count", "area")

    val THROWS = setOf("throwError", "throwWarning", "throwOther")

    val VALIDATOR = THROWS + setOf(
        "group", "fixAdd", "fixRemove", "fixChangeKey", "fixDeleteObject", "suggestAlternative",
        "assertMatch", "assertNoMatch",
    )

    val META = setOf("title", "description", "author", "version", "link", "min-josm-version", "watch-modified", "icon")

    val IGNORED_PREFIXES = listOf("icon-", "symbol-", "repeat-image")

    fun isIgnoredProperty(name: String): Boolean = IGNORED_PREFIXES.any { name.startsWith(it) }

    val BUILTINS = setOf(
        "tag", "parent_tag", "parent_tags", "child_tag", "child_tags", "has_tag_key", "regexp_test", "regexp_match",
        "to_int", "to_long", "to_float", "to_double", "to_boolean", "split", "count", "concat", "join", "join_list",
        "tr", "cond", "any", "coalesce", "number_of_tags", "osm_id", "osm_type", "parent_osm_id", "is_similar",
        "length", "upper", "lower", "trim", "title", "get", "list", "max", "min", "abs", "round", "ceil", "floor",
        "sqrt", "replace", "substring", "startswith", "endswith", "uniq", "sort", "uniq_list", "sort_list",
        "count_roles", "rgb", "rgba", "str", "boolean", "not", "equal", "mod", "plus", "minus", "times",
        "divided_by", "greater", "less", "greater_equal", "less_equal", "siunit_length", "tag_regex", "URL_decode",
        "URL_encode", "XML_encode", "int", "signum", "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "log",
        "exp", "degree_to_radians", "to_short", "to_byte",
    )

    val HOST = setOf(
        "waylength", "areasize", "odd_segment", "network_island_size", "half_width", "connected_value", "inside", "outside",
        "is_right_hand_traffic", "at", "gpx_distance", "heading",
    )

    val UNSUPPORTED = setOf(
        "JOSM_pref", "JOSM_search", "prop", "is_prop_set", "role", "index", "setting", "osm_user_name", "osm_user_id",
        "osm_version", "osm_changeset_id", "osm_timestamp", "CRC32_checksum", "URL_decode", "URL_encode",
        "auto_increment", "is_clockwise", "is_anticlockwise", "alpha", "red", "green", "blue", "hsb_color",
        "html2color", "color2html", "brightness", "cardinal_to_radians", "parent_way_angle", "number_of_children",
        "metric", "zoom_level", "point_in_polygon", "unique_id", "keys", "eval_hash", "parent_osm_primitives",
        "println", "print", "child_osm_primitives", "count_members",
    )

    val UNINTERESTING = setOf(
        "source", "source_ref", "note", "comment", "created_by", "converted_by", "fixme", "FIXME", "description",
        "attribution", "watch", "odbl", "odbl:note",
    )

    val UNINTERESTING_PREFIXES = listOf("source:", "watch:")

    fun isUninteresting(key: String): Boolean = key in UNINTERESTING || UNINTERESTING_PREFIXES.any { key.startsWith(it) }
}
