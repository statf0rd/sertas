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
    // Нативный захват системного звука (Фаза B). Собрать: scripts/build-macos-audio-dylib.sh.
    // Если файла нет — System.load() мягко падает, звук демо просто недоступен.
    applicationDefaultJvmArgs = listOf(
        "-Dsertas.audio.dylib=${rootDir}/media-engine/build/native/libsertas_audio.dylib"
    )
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":signaling-client"))
    implementation(project(":media"))
    implementation(project(":media-engine"))
}

// Проброс -Dsertas.* из gradle-вызова в приложение, напр.:
//   ./gradlew :app-client:run -Dsertas.demoaudio=on
tasks.named<JavaExec>("run") {
    listOf("sertas.demoaudio", "sertas.mixer", "sertas.server", "sertas.turn").forEach { p ->
        System.getProperty(p)?.let { systemProperty(p, it) }
    }
}
