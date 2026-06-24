pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "sertas"

include("protocol", "signaling-server", "signaling-client", "media", "media-engine", "app-client")
