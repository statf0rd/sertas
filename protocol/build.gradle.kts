plugins {
    `java-library`
}

dependencies {
    // Аннотации Jackson попадают в публичный API (на SignalMessage) — экспонируем через api,
    // чтобы потребители видели их на compile-classpath.
    api(libs.jackson.annotations)
    // ObjectMapper — деталь реализации SignalCodec.
    implementation(libs.jackson.databind)
}
