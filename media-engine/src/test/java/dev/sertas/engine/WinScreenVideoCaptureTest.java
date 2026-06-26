package dev.sertas.engine;

import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Проверяет конвертацию BGRA → I420 (раскладка/размеры) на синтетическом кадре —
 * это путь Windows-захвата (DXGI отдаёт B8G8R8A8). Сама DLL собирается и проверяется
 * на экспорт символов в CI (windows-capture.yml); реальный захват — на железе.
 */
class WinScreenVideoCaptureTest {

    @Test
    void convertsSyntheticBgraToI420() {
        WebRtcEngine engine = WebRtcEngine.headless(); // грузит нативную webrtc
        try {
            int w = 1280;
            int h = 720;
            byte[] bgra = new byte[w * h * 4];
            for (int i = 0; i < w * h; i++) {
                bgra[i * 4] = (byte) (i & 0xFF);       // B
                bgra[i * 4 + 1] = (byte) ((i >> 8) & 0xFF); // G
                bgra[i * 4 + 2] = (byte) 0x40;         // R
                bgra[i * 4 + 3] = (byte) 0xFF;         // A
            }
            NativeI420Buffer i420 = NativeI420Buffer.allocate(w, h);
            assertDoesNotThrow(() -> VideoBufferConverter.convertToI420(bgra, i420, FourCC.ARGB));
            assertEquals(w, i420.getWidth());
            assertEquals(h, i420.getHeight());
            i420.release();
        } finally {
            engine.dispose();
        }
    }
}
