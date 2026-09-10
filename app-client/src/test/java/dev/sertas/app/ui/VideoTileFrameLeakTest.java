package dev.sertas.app.ui;

import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.sertas.engine.WebRtcEngine;
import javafx.application.Platform;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * Принятый кадр — нативный объект со счётчиком ссылок: JNI-сторона webrtc-java
 * копирует буфер и делает {@code AddRef} перед вызовом Java-sink'а, поэтому sink
 * ОБЯЗАН позвать {@link VideoFrame#release()}. Без этого каждый кадр остаётся в
 * нативной куче: 1080p ≈ 3МБ × 30 кадров/с ≈ 90МБ/с — за десяток минут показа
 * десятки гигабайт «программной памяти» и своп (Java-куча при этом чистая,
 * OutOfMemoryError не возникает).
 *
 * <p>Тест гонит кадры через sink плитки и смотрит на RSS процесса. При утечке
 * прирост равен объёму прогнанных кадров; без неё — околонулевой.
 */
class VideoTileFrameLeakTest {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int FRAMES = 200;
    /** Прогнано ~275МБ кадров; порог с большим запасом от шума аллокатора. */
    private static final long MAX_GROWTH_BYTES = 150L * 1024 * 1024;

    @Test
    void doesNotLeakNativeMemoryPerReceivedFrame() throws Exception {
        assumeTrue(rss() > 0, "RSS процесса недоступен (не unix?)");
        startJavaFx();

        WebRtcEngine engine = WebRtcEngine.headless();
        try {
            VideoTrack track = engine.createVideoTrack("screen",
                    new dev.onvoid.webrtc.media.video.CustomVideoSource());
            VideoTile tile = new VideoTile(track);

            // Прогрев: первый кадр выделяет общий BGRA-буфер плитки.
            tile.onFrame(newFrame());
            long before = rss();

            for (int i = 0; i < FRAMES; i++) {
                tile.onFrame(newFrame());
            }
            System.gc();
            long growth = rss() - before;

            assertTrue(growth < MAX_GROWTH_BYTES,
                    "нативная память выросла на " + growth / (1024 * 1024) + "МБ за " + FRAMES
                            + " кадров — принятые кадры не освобождаются (нет VideoFrame.release())");
            tile.dispose();
        } finally {
            engine.dispose();
        }
    }

    /** Кадр, каким его отдаёт JNI: свежий буфер со счётчиком ссылок 1. */
    private static VideoFrame newFrame() {
        return new VideoFrame(NativeI420Buffer.allocate(WIDTH, HEIGHT), 0L);
    }

    private static void startJavaFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyRunning) {
            ready.countDown();
        }
        assumeTrue(ready.await(20, TimeUnit.SECONDS), "JavaFX toolkit не поднялся");
    }

    /** Resident set size процесса в байтах (0 — не удалось определить). */
    private static long rss() {
        try {
            Process p = new ProcessBuilder("ps", "-o", "rss=", "-p",
                    String.valueOf(ProcessHandle.current().pid())).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = r.readLine();
                return line == null ? 0 : Long.parseLong(line.trim()) * 1024;
            }
        } catch (Exception e) {
            return 0;
        }
    }
}
