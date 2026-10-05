plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
}

android {
    namespace = "com.personalos.mlsdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // The manifest is JSON; the same parser the app uses.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // The store does blocking IO (hash, download); it must move off the caller's
    // dispatcher itself, so a UI coroutine cannot trigger NetworkOnMainThread.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
