plugins {
    id("com.android.application")
}

android {
    namespace = "com.smsprobe.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.smsprobe.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            kotlin.directories += "src/main/kotlin"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}