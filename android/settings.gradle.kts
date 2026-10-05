pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "personalos"
include(":app")
// The model SDK: locate/fetch/verify shared on-device models. Its own module so
// it can be lifted into a hub app (and reused by other apps) unchanged.
include(":mlsdk")