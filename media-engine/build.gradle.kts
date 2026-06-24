// Изоляция нативной зависимости webrtc-java. Содержит жизненный цикл
// PeerConnectionFactory и оркестрацию меша (по одному RTCPeerConnection на пира).

plugins {
    `java-library`
}

val webrtcVersion = "0.14.0"

/** Классификатор нативного артефакта webrtc-java для хоста сборки. */
fun webrtcClassifier(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val a = when (arch) {
        "aarch64", "arm64" -> "aarch64"
        "x86_64", "amd64" -> "x86_64"
        else -> arch
    }
    return when {
        os.contains("mac") || os.contains("darwin") -> "macos-$a"
        os.contains("win") -> "windows-x86_64"
        else -> "linux-$a"
    }
}

dependencies {
    api("dev.onvoid.webrtc:webrtc-java:$webrtcVersion")
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:$webrtcVersion:${webrtcClassifier()}")

    implementation(project(":protocol"))
    implementation(project(":signaling-client"))
    implementation(project(":media"))

    // Полный интеграционный тест поднимает реальный сигналинг-сервер.
    testImplementation(project(":signaling-server"))
}
