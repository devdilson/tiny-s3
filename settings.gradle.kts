import java.util.Locale

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

plugins {
    id("com.diffplug.spotless") version "6.24.0" apply false
}

rootProject.name = "tiny-s3"

include("lib")


rootProject.children.forEach {
        subProject ->
    subProject.name = (rootProject.name + "-" + subProject.name).lowercase(Locale.getDefault())
}