pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "sertas"

include("protocol", "signaling-server", "signaling-client", "media", "app-client")
