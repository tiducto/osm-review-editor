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
    environment("OSM_REVIEW_TEST_GOL", System.getenv("OSM_REVIEW_TEST_GOL") ?: "")
}
