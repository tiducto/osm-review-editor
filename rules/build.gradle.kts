plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    api(project(":shared"))
    api(libs.geodesk)
    api(libs.jts.core)
    implementation(libs.eclipse.collections)
    implementation(libs.asm)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
}

tasks.test {
    // Integration tests against a real GOL run only when OSM_REVIEW_TEST_GOL points at one.
    environment("OSM_REVIEW_TEST_GOL", System.getenv("OSM_REVIEW_TEST_GOL") ?: "")
}
