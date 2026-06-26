package dev.sertas.engine;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Воспроизведение звука демонстрации у зрителя через {@code javax.sound}. Звук демо
 * приходит на ОТДЕЛЬНОМ (headless) движке — у него нет ADM-вывода, поэтому играем
 * сами. PCM из {@code AudioTrackSink.onData} → очередь → отдельный поток пишет в
 * {@link SourceDataLine}. Линия открывается по формату первого кадра.
 *
 * <p>Голос остаётся на реальном ADM (с AEC); у звука демо своего AEC нет
 * (эхо в микрофон зрителя без наушников — приемлемо для громкой музыки).
 */
public final class JavaSoundDemoPlayer {

    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(64);
    private volatile SourceDataLine line;
    private volatile boolean running;
    private volatile boolean failed;
    private Thread thread;

    /** Принять кадр S16LE (из {@code AudioTrackSink.onData}); открывает линию при первом кадре. */
    public void offer(byte[] s16, int sampleRate, int channels) {
        if (failed) {
            return;
        }
        if (line == null) {
            open(sampleRate, channels);
            if (line == null) {
                return;
            }
        }
        byte[] copy = s16.clone(); // буфер webrtc переиспользуется
        if (!queue.offer(copy)) {
            queue.poll();          // переполнение → выкидываем старейший
            queue.offer(copy);
        }
    }

    private synchronized void open(int sampleRate, int channels) {
        if (line != null || failed) {
            return;
        }
        try {
            AudioFormat fmt = new AudioFormat(sampleRate, 16, channels, true, false);
            SourceDataLine l = AudioSystem.getSourceDataLine(fmt);
            l.open(fmt, sampleRate * channels * 2 / 5); // ~200мс буфер
            l.start();
            line = l;
            running = true;
            thread = new Thread(this::drain, "demo-audio-playout");
            thread.setDaemon(true);
            thread.start();
        } catch (Exception e) {
            failed = true;
            System.err.println("sertas: нет аудио-вывода для звука демо: " + e.getMessage());
        }
    }

    private void drain() {
        while (running) {
            try {
                byte[] b = queue.poll(100, TimeUnit.MILLISECONDS);
                SourceDataLine l = line;
                if (b != null && l != null) {
                    l.write(b, 0, b.length);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public void stop() {
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            thread = null;
        }
        SourceDataLine l = line;
        line = null;
        if (l != null) {
            try {
                l.stop();
                l.close();
            } catch (Exception ignored) {
                // линия уже закрыта
            }
        }
        queue.clear();
    }
}
