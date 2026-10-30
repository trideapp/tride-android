// TridePlayer for Android — the example of the TrideKit-Android snapshot. TrideKit comes as binaries from
// the snapshot's `maven/` folder (two levels up); everything else from Google and Maven Central.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri(file("../../maven")) } }
            filter { includeGroup("studio.tride") }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "TridePlayer"
include(":app")
