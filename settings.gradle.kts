rootProject.name = "osm-review-editor"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// KMP (jvm + js): wire models and TagDiff logic shared by server, MCP and the Vue UI.
include(":shared")
// The rule(...) DSL and the rule definitions, over GeoDesk GOL queries.
include(":rules")
// Ktor app: REST, WebSocket, MCP, JOSM remote control and the core (drafts, merge, OSM API).
include(":server")
