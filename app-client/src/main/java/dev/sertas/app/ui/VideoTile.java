package dev.sertas.app.ui;

import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import javafx.animation.AnimationTimer;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;

import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Плитка удалённого видео. Кадры приходят на нативном WebRTC-потоке: там их
 * конвертируем I420 → BGRA в общий direct-буфер; на FX-потоке через
 * {@link AnimationTimer} обновляем {@link PixelBuffer} (не чаще refresh).
 * Пока кадров нет (никто не демонстрирует) — плитка скрыта.
 */
public final class VideoTile {

    private static final long HIDE_AFTER_NS = 2_000_000_000L;

    private final VideoTrack track;
    private final VideoTrackSink sink = this::onFrame;
    private final ImageView view = new ImageView();
    private final ReentrantLock lock = new ReentrantLock();
    private final AnimationTimer timer;

    private ByteBuffer buffer;
    private int width;
    private int height;
    private boolean dirty;
    private boolean sizeChanged;
    private PixelBuffer<ByteBuffer> pixelBuffer;
    private volatile long lastFrameNs;

    public VideoTile(VideoTrack track) {
        this.track = track;
        view.setPreserveRatio(true);
        view.setManaged(false);
        view.setVisible(false);
        // Видео заполняет ширину контейнера (на весь доступный размер окна).
        view.setFitWidth(640);
        view.parentProperty().addListener((obs, oldParent, parent) -> {
            view.fitWidthProperty().unbind();
            if (parent instanceof Region region) {
                view.fitWidthProperty().bind(region.widthProperty().subtract(24));
            } else {
                view.setFitWidth(640);
            }
        });
        track.addSink(sink);
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                render();
            }
        };
        timer.start();
    }

    /** Узел для размещения в сцене. */
    public ImageView node() {
        return view;
    }

    /** Нативный поток: конвертация кадра в BGRA. */
    private void onFrame(VideoFrame frame) {
        try {
            int fw = frame.buffer.getWidth();
            int fh = frame.buffer.getHeight();
            lock.lock();
            try {
                if (buffer == null || fw != width || fh != height) {
                    buffer = ByteBuffer.allocateDirect(fw * fh * 4);
                    width = fw;
                    height = fh;
                    sizeChanged = true;
                }
                // libyuv FourCC.ARGB = байты B,G,R,A в памяти = JavaFX BYTE_BGRA.
                VideoBufferConverter.convertFromI420(frame.buffer, buffer, FourCC.ARGB);
                dirty = true;
            } finally {
                lock.unlock();
            }
            lastFrameNs = System.nanoTime();
        } catch (Exception ignored) {
            // битый/неподдержанный кадр — пропускаем
        }
    }

    /** FX-поток: применяем последний кадр и управляем видимостью. */
    private void render() {
        lock.lock();
        try {
            if (sizeChanged && buffer != null) {
                pixelBuffer = new PixelBuffer<>(width, height, buffer, PixelFormat.getByteBgraPreInstance());
                view.setImage(new WritableImage(pixelBuffer));
                sizeChanged = false;
                dirty = false;
            } else if (dirty && pixelBuffer != null) {
                pixelBuffer.updateBuffer(b -> null);
                dirty = false;
            }
        } finally {
            lock.unlock();
        }
        boolean active = lastFrameNs != 0 && (System.nanoTime() - lastFrameNs) < HIDE_AFTER_NS;
        view.setVisible(active);
        view.setManaged(active);
    }

    public void dispose() {
        timer.stop();
        int id = System.identityHashCode(track);
        // Диагностика use-after-free: если в логе есть «removeSink CALLING» без
        // парной «DONE» как последняя строка перед падением — нативный трек уже
        // освобождён (pc.close/factory.dispose) и removeSink бьёт по висячему
        // указателю. Намеренно НЕ читаем track.getState() — оно само разыменует
        // тот же handle и крашнет процесс раньше времени.
        System.err.println("[life] VideoTile.dispose track=" + id
                + " thread=" + Thread.currentThread().getName() + " t=" + System.nanoTime());
        try {
            System.err.println("[life] removeSink CALLING track=" + id);
            track.removeSink(sink);
            System.err.println("[life] removeSink DONE track=" + id);
        } catch (RuntimeException ignored) {
            // трек уже освобождён (нативный SIGSEGV этим НЕ ловится — см. лог выше)
        }
    }
}
