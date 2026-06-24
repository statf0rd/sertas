plugins {
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

javafx {
    version = "21.0.4"
    modules("javafx.controls")
}

application {
    mainClass.set("dev.sertas.app.SertasApp")
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":signaling-client"))
    implementation(project(":media"))
    implementation(project(":media-engine"))
}
