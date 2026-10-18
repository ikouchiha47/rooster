import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("org.jlleitschuh.gradle.ktlint")
}

// Release signing reads the shared credentials file **outside the repo**:
//   ~/.credentials  ->  rooster_app_signing_key=<path to .jks>
//                       rooster_app_store_password=<password>
// A debug build needs none of it. `assembleRelease` needs both, and fails loudly
// if they are missing rather than shipping an unsigned APK.
val credentials: Map<String, String> =
    File(System.getProperty("user.home"), ".credentials")
        .takeIf { it.isFile }
        ?.readLines()
        ?.mapNotNull { line ->
            if (line.trimStart().startsWith("#")) return@mapNotNull null
            val at = line.indexOf('=')
            if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
        }?.toMap()
        ?: emptyMap()
val releaseStore: String? = credentials["rooster_app_signing_key"]
val releaseStorePassword: String? = credentials["rooster_app_store_password"]

android {
    namespace = "com.personalos.app"
    compileSdk = 36

    signingConfigs {
        if (releaseStore != null && releaseStorePassword != null) {
            create("release") {
                storeFile = File(releaseStore)
                storePassword = releaseStorePassword
                keyAlias = "rooster"
                keyPassword = releaseStorePassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Sign with the real key when credentials exist; otherwise leave the
            // Gradle default (unsigned) so a missing file cannot masquerade as signed.
            if (releaseStore != null && releaseStorePassword != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

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

    packaging {
        // The tokenizer's native lib is loaded from disk, so it must be extracted
        // rather than mapped out of the APK.
        jniLibs.useLegacyPackaging = true
        // DJL/JNA publish native artifacts for every desktop platform under
        // non-standard resource paths that abiFilters never prune; they were dead
        // weight in the APK (verified in mofy). Drop them.
        resources {
            excludes +=
                setOf(
                    "native/lib/win-x86_64/**",
                    "native/lib/osx-aarch64/**",
                    "native/lib/osx-x86_64/**",
                    "native/lib/linux-x86_64/**",
                    "com/sun/jna/**",
                )
        }
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

    // The model SDK: locate/fetch/verify shared on-device models. Kept a separate
    // module so it can be lifted into the hub app and reused by other apps.
    implementation(project(":mlsdk"))

    // On-device embedding — EmbeddingGemma-300m, 256-dim, via LiteRT's Interpreter
    // API on CPU. Versions pinned to what mofy proved on this device: litert 1.4.0
    // (2.2.0 aborts in Environment.nativeCreate here, and conflicts on namespace),
    // and DJL tokenizers 0.33.0 with its native payload.
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("ai.djl.huggingface:tokenizers:0.33.0")
    implementation("ai.djl.android:tokenizer-native:0.33.0")

    // ONNX Runtime, for the batched EmbeddingGemma. TFLite's mofy export is fixed
    // `[1,256]`, so it can never embed more than one text per run; the ONNX export
    // keeps a dynamic batch axis, which is what makes a whole-store retag tractable
    // (measured: ~1.6x, and much faster in absolute terms than the int8 TFLite).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

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
