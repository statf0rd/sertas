package dev.sertas.engine;

import dev.onvoid.webrtc.media.video.CustomVideoSource;

/** Нативный захват экрана в {@link CustomVideoSource} (macOS — ScreenCaptureKit, Windows — DXGI). */
public interface ScreenVideoCapture {

    /** Начать захват экрана {@code width}×{@code height} @ {@code fps} в {@code sink}. */
    void start(CustomVideoSource sink, int width, int height, int fps);

    void stop();

    /** Сколько кадров протолкнуто в источник (диагностика). */
    long framesPushed();
}
