pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "light-sdk"

includeBuild("plugin")

include(":lint-rules")
include(":sdk")
include(":app")
