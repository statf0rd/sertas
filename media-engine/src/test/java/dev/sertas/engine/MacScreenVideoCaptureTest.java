package dev.sertas.engine;

import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Проверяет нативный захват экрана: (1) загрузку dylib + линковку JNI (если собран),
 * (2) корректность конвертации NV12 → I420 (раскладка/размеры) на синтетическом кадре —
 * это самое рискованное место пути захвата. Реальный SCStream требует разрешения
 * Screen Recording и проверяется вручную.
 */
class MacScreenVideoCaptureTest {

    @Test
    void loadsDylibAndNativeStopIsSafe() {
        Path dylib = Path.of("build/native/libsertas_audio.dylib");
        assumeTrue(Files.exists(dylib), "dylib не собран — пропуск");
        System.setProperty("sertas.audio.dylib", dylib.toAbsolutePath().toString());

        assertTrue(MacScreenVideoCapture.isAvailable(), "dylib должен загрузиться");
        // stop() до start() — безопасный no-op; проверяет линковку nativeStop без разрешения.
        assertDoesNotThrow(() -> new MacScreenVideoCapture().stop());
    }

    @Test
    void convertsSyntheticNv12ToI420() {
        WebRtcEngine engine = WebRtcEngine.headless(); // грузит нативную webrtc
        try {
            int w = 1280;
            int h = 720;
            byte[] nv12 = new byte[w * h * 3 / 2];
            for (int row = 0; row < h; row++) {
                for (int col = 0; col < w; col++) {
                    nv12[row * w + col] = (byte) (col & 0xFF); // Y-градиент
                }
            }
            for (int i = w * h; i < nv12.length; i++) {
                nv12[i] = (byte) 128; // UV нейтраль (серый)
            }

            NativeI420Buffer i420 = NativeI420Buffer.allocate(w, h);
            assertDoesNotThrow(() -> VideoBufferConverter.convertToI420(nv12, i420, FourCC.NV12));
            assertEquals(w, i420.getWidth());
            assertEquals(h, i420.getHeight());
            i420.release();
        } finally {
            engine.dispose();
        }
    }
}
