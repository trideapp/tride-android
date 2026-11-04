// TridePlayer for Android — the example of the TrideKit-Android snapshot. TrideKit and the Filament it is
// built on (`studio.tride.filament`, Tride's build) come as binaries from the snapshot's `maven/` folder
// (two levels up); everything else from Google and Maven Central.
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
            filter {
                includeGroup("studio.tride")             // TrideKit
                includeGroup("studio.tride.filament")    // the Filament TrideKit is built on
            }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "TridePlayer"
include(":app")
