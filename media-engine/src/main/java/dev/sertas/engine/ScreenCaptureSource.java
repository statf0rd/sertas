package dev.sertas.engine;

import dev.onvoid.webrtc.media.video.VideoDesktopSource;
import dev.onvoid.webrtc.media.video.desktop.DesktopSource;
import dev.onvoid.webrtc.media.video.desktop.ScreenCapturer;

import java.util.List;

/**
 * Демонстрация экрана через встроенный {@link VideoDesktopSource} (libwebrtc
 * DesktopCapturer; на macOS использует ScreenCaptureKit под капотом). Это
 * быстрый старт; апгрейд на нативный SCStream/WGC — отдельным шагом, если FPS
 * встроенного захвата недостаточен (см. дизайн §5.1, §7).
 *
 * <p>Захват видео; СИСТЕМНЫЙ ЗВУК демонстрации добавляется отдельным треком
 * через нативный хелпер + CustomAudioSource (Фаза 3).
 */
public final class ScreenCaptureSource {

    /** Пресеты качества: компромисс FPS ↔ разрешение под полосу меша. */
    public enum Quality {
        SMOOTH(1280, 720, 60),     // плавность: ниже разрешение, выше FPS
        BALANCED(1920, 1080, 30),
        CRISP(2560, 1440, 15);     // чёткость: выше разрешение, ниже FPS

        final int maxWidth;
        final int maxHeight;
        final int fps;

        Quality(int maxWidth, int maxHeight, int fps) {
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
            this.fps = fps;
        }
    }

    private final VideoDesktopSource source = new VideoDesktopSource();

    /**
     * @param screenId id экрана из {@link #screens()}
     * @param quality  пресет качества
     */
    public ScreenCaptureSource(long screenId, Quality quality) {
        source.setSourceId(screenId, false); // false = экран (true было бы окно)
        source.setFrameRate(quality.fps);
        source.setMaxFrameSize(quality.maxWidth, quality.maxHeight);
    }

    /** Доступные экраны. На macOS требует разрешения Screen Recording (TCC). */
    public static List<DesktopSource> screens() {
        ScreenCapturer capturer = new ScreenCapturer();
        try {
            return capturer.getDesktopSources();
        } finally {
            capturer.dispose();
        }
    }

    public VideoDesktopSource source() {
        return source;
    }

    public void start() {
        source.start();
    }

    public void stop() {
        source.stop();
    }

    public void dispose() {
        source.dispose();
    }
}
