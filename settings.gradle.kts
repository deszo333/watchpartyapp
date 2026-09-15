pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // WebRTC prebuilt Android SDK mirror
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "WatchParty"
include(":app")
