package dev.sertas.engine;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Проверяет загрузку нативной библиотеки захвата и линковку JNI-методов. Запускается
 * только если dylib собран ({@code scripts/build-macos-audio-dylib.sh}) — иначе пропуск.
 * Реальный захват SCStream требует разрешения Screen Recording и проверяется вручную.
 */
class MacSystemAudioCaptureTest {

    @Test
    void loadsDylibAndNativeStopIsSafe() {
        Path dylib = Path.of("build/native/libsertas_audio.dylib");
        assumeTrue(Files.exists(dylib), "dylib не собран — пропуск");
        System.setProperty("sertas.audio.dylib", dylib.toAbsolutePath().toString());

        assertTrue(MacSystemAudioCapture.isAvailable(), "dylib должен загрузиться");
        // stop() до start() — безопасный no-op; проверяет линковку nativeStop без разрешения.
        assertDoesNotThrow(() -> new MacSystemAudioCapture().stop());
    }
}
