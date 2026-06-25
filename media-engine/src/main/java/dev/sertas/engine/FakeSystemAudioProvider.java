package dev.sertas.engine;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Фейковый источник системного звука: синус заданной частоты, планарный стерео
 * 48кГц, чанки по 10мс (480 фреймов) на фоновом потоке. Для headless-тестов
 * пайплайна и как временный источник Фазы A (до нативного ScreenCaptureKit в
 * Фазе B). Частота {@code <= 0} → тишина.
 */
public final class FakeSystemAudioProvider implements SystemAudioProvider {

    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAMES_PER_CHUNK = SAMPLE_RATE / 100; // 10мс = 480

    private final double frequencyHz;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread thread;
    private long phase;

    public FakeSystemAudioProvider(double frequencyHz) {
        this.frequencyHz = frequencyHz;
    }

    @Override
    public void start(PcmSink sink) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> run(sink), "fake-system-audio");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    @Override
    public void stop() {
        running.set(false);
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
        thread = null;
    }

    private void run(PcmSink sink) {
        while (running.get()) {
            float[] left = new float[FRAMES_PER_CHUNK];
            float[] right = new float[FRAMES_PER_CHUNK];
            for (int i = 0; i < FRAMES_PER_CHUNK; i++) {
                float s = frequencyHz <= 0 ? 0f
                        : (float) (0.25 * Math.sin(2 * Math.PI * frequencyHz * (phase++) / SAMPLE_RATE));
                left[i] = s;
                right[i] = s;
            }
            sink.onPcm(left, right, SAMPLE_RATE);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
