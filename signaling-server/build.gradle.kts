plugins {
    application
}

application {
    mainClass.set("dev.sertas.signaling.SignalingServer")
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.javalin)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(project(":signaling-client"))
}
