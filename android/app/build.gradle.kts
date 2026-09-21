plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "com.personalos.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.personalos.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        ndk {
            // One personal phone, arm64-v8a. The only native library in the tree is
            // androidx.graphics.path (~10 KB per ABI), so this saves ~27 KB today —
            // it is here so a future native dependency cannot quietly add four
            // ABIs and multiply that.
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Data-layer classes log via android.util.Log; without this every unit
        // test touching a writer, seeder or syncer dies with "not mocked".
        // Returning defaults keeps those classes testable on the JVM.
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("main") {
            kotlin.directories += "src/main/kotlin"
        }
        getByName("test") {
            kotlin.directories += "src/test/kotlin"
        }
        // The FORCE_SYNC trigger is debug-only, so its sources live here and a
        // release APK cannot contain them.
        getByName("debug") {
            kotlin.directories += "src/debug/kotlin"
        }
    }
}

ksp {
    // Export the Room schema so migrations are written against (and verified
    // against) a checked-in baseline instead of guessed DDL.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")

    implementation("androidx.room:room-runtime:2.8.1")
    implementation("androidx.room:room-ktx:2.8.1")
    ksp("androidx.room:room-compiler:2.8.1")

    // Bundled SQLite, not the platform's. The platform library has FTS3/FTS4 but
    // no FTS5, and the trigram tokenizer that gives Messages search its infix
    // matching only ships with FTS5. This payload is SQLite 3.50.1; Room opens
    // it through BundledSQLiteDriver (see AppDatabase), so every connection in
    // the app - migration, ingest, search - is FTS5-capable.
    implementation("androidx.sqlite:sqlite-bundled:2.7.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // ULID: stable, sortable, collision-free-offline ids (docs/ARCHITECTURE.md §11.1)
    implementation("com.aallam.ulid:ulid-kotlin:1.6.0")

    // HTML parsing for article summaries. Chosen over hand-rolled regex because
    // the input is arbitrary third-party HTML: jsoup decodes the full HTML5
    // entity set (~2,231 names) and tolerates malformed markup.
    implementation("org.jsoup:jsoup:1.23.2")

    // Scheduled background ingest
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")

    // Real SQLite in JVM tests, so migration SQL can be executed and compared
    // against the exported Room schema (see MigrationSchemaTest).
    testImplementation("org.xerial:sqlite-jdbc:3.53.4.0")
}
