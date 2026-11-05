plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("eu.tiducto.osmreview.server.ApplicationKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":rules"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.xmlutil.serialization)

    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.json)
    implementation(libs.exposed.java.time)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.hikaricp)
    implementation(libs.postgresql.jdbc)

    implementation(libs.mcp.kotlin.sdk.server)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.testcontainers.postgresql)
}

val idTaggingSchema = rootProject.layout.projectDirectory.dir("node_modules/@openstreetmap/id-tagging-schema")
val idTaggingSchemaResources = layout.buildDirectory.dir("generated/id-tagging-schema")
val copyIdTaggingSchema by tasks.registering(Sync::class) {
    group = "build"
    description = "Copies the iD tagging schema (presets, fields, categories, en and cs translations) from node_modules into the server resources."
    val packageJson = idTaggingSchema.file("package.json").asFile
    doFirst {
        check(packageJson.exists()) { "${packageJson.parentFile} is missing; run npm install first" }
    }
    from(packageJson) { into("id-tagging-schema/dist") }
    from(idTaggingSchema.dir("dist")) {
        include(
            "presets.min.json",
            "fields.min.json",
            "preset_categories.min.json",
            "translations/en.min.json",
            "translations/cs.min.json",
        )
        rename("(.*)\\.min\\.json", "$1.json")
        into("id-tagging-schema/dist")
    }
    into(idTaggingSchemaResources)
}
sourceSets.main {
    resources.srcDir(files(idTaggingSchemaResources).builtBy(copyIdTaggingSchema))
}

val webDist = rootProject.layout.projectDirectory.dir("web/dist")
tasks.processResources {
    from(webDist) { into("static") }
}

tasks.processResources {
    from(rootProject.file("VERSION")) { rename { "version.txt" } }
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.test {
    environment("OSM_REVIEW_TEST_GOL", System.getenv("OSM_REVIEW_TEST_GOL") ?: "")
}
