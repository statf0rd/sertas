package dev.sertas.engine;

import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrameBuffer;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Диагностика рендера: захватывает экран тем же трактом, что приложение, и
 * сохраняет первый кадр в PNG двумя способами (ARGB и BGRA), интерпретируя байты
 * как JavaFX BYTE_BGRA. Позволяет визуально проверить корректность цвета и
 * чёткость. Запуск: SERTAS_CAPTURE_TEST=1 ./gradlew :media-engine:test --tests '*ScreenCaptureRenderTest*'
 */
class ScreenCaptureRenderTest {

    @Test
    void captureScreenAndSavePngs() throws Exception {
        assumeTrue("1".equals(System.getenv("SERTAS_CAPTURE_TEST")), "set SERTAS_CAPTURE_TEST=1");

        WebRtcEngine engine = WebRtcEngine.headless();
        List<DesktopSource> screens = ScreenCaptureSource.screens();
        System.out.println("SCREENS: " + screens.size());
        for (DesktopSource s : screens) {
            System.out.println("  screen id=" + s.id + " title=" + s.title);
        }
        assumeTrue(!screens.isEmpty(), "нет экранов / нет разрешения Screen Recording");

        ScreenCaptureSource screen = new ScreenCaptureSource();
        screen.select(screens.get(0).id, ScreenCaptureSource.Quality.BALANCED);
        VideoTrack track = engine.createVideoTrack("screen", screen.source());

        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean processed = new AtomicBoolean();
        VideoTrackSink sink = frame -> {
            if (!processed.compareAndSet(false, true)) {
                return;
            }
            try {
                int w = frame.buffer.getWidth();
                int h = frame.buffer.getHeight();
                savePng(frame.buffer, w, h, FourCC.ARGB, "/tmp/sertas_screen_argb.png");
                savePng(frame.buffer, w, h, FourCC.BGRA, "/tmp/sertas_screen_bgra.png");
                System.out.println("SAVED FRAME " + w + "x" + h);
            } catch (Exception e) {
                System.out.println("CONVERT ERROR: " + e);
            } finally {
                done.countDown();
            }
        };
        track.addSink(sink);
        screen.start();
        boolean got = done.await(20, TimeUnit.SECONDS);
        screen.stop();
        engine.dispose();
        System.out.println(got ? "RESULT: FRAME CAPTURED" : "RESULT: NO FRAME (разрешение Screen Recording?)");
    }

    /** Конвертирует I420 кадр выбранным FourCC и пишет PNG, читая байты как B,G,R,A. */
    private static void savePng(VideoFrameBuffer buffer, int w, int h, FourCC fourCC, String path) throws Exception {
        ByteBuffer bb = ByteBuffer.allocateDirect(w * h * 4);
        VideoBufferConverter.convertFromI420(buffer, bb, fourCC);
        byte[] bytes = new byte[w * h * 4];
        bb.rewind();
        bb.get(bytes);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < w * h; i++) {
            int b = bytes[i * 4] & 0xFF;
            int g = bytes[i * 4 + 1] & 0xFF;
            int r = bytes[i * 4 + 2] & 0xFF;
            img.setRGB(i % w, i / w, (r << 16) | (g << 8) | b);
        }
        ImageIO.write(img, "png", new File(path));
    }
}
