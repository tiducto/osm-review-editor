plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(25)
    jvm()
    js {
        outputModuleName = "osm-review-shared"
        browser()
        binaries.library()
        generateTypeScriptDefinitions()
        compilerOptions {
            target = "es2015"
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }

    compilerOptions {
        optIn.add("kotlin.js.ExperimentalJsExport")
    }
}

val generateTsModels by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generates web/src/generated/models.ts from the shared @Serializable models."
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    dependsOn(jvmMain.compileTaskProvider)
    classpath = files(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
    mainClass.set("eu.tiducto.osmreview.shared.TsModelGeneratorKt")
    args(rootProject.file("web/src/generated/models.ts").absolutePath)
}
