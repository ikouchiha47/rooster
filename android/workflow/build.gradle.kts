plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("app.cash.sqldelight") version "2.3.2"
    id("org.jlleitschuh.gradle.ktlint")
}

sqldelight {
    databases {
        register("WorkflowDatabase") {
            packageName.set("com.personalos.workflow.db")
        }
    }
}

kotlin {
    // The engine is pure Kotlin; `jvm()` is where it is tested and where a
    // non-Android consumer runs. iOS targets are added here when Xcode is present
    // (ADR 0009 §1) — the API is already multiplatform.
    jvm()

    android {
        namespace = "com.personalos.workflow"
        compileSdk = 36
        minSdk = 26
    }

    sourceSets {
        commonMain.dependencies {
            // The engine sequences work with coroutines; a store is a port, so SQL
            // lives behind RunStore and the engine never sees it (ADR 0009 §6).
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            implementation("app.cash.sqldelight:runtime:2.3.2")
        }
        androidMain.dependencies {
            implementation("app.cash.sqldelight:android-driver:2.3.2")
        }
        jvmMain.dependencies {
            implementation("app.cash.sqldelight:sqlite-driver:2.3.2")
            implementation("org.xerial:sqlite-jdbc:3.53.4.0")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(kotlin("test-junit"))
            implementation("junit:junit:4.13.2")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
            implementation("app.cash.sqldelight:sqlite-driver:2.3.2")
            implementation("org.xerial:sqlite-jdbc:3.53.4.0")
        }
    }
}
