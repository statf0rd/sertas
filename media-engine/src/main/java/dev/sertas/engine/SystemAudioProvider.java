package dev.sertas.engine;

/**
 * Источник системного звука демонстрации. Отдаёт планарный стерео Float32 в
 * {@link PcmSink}. Реализации: нативный ScreenCaptureKit (Фаза B) или
 * {@link FakeSystemAudioProvider} (тесты и временный источник Фазы A).
 */
public interface SystemAudioProvider {

    /** Начать захват, отдавая планарный стерео Float32 в {@code sink}. */
    void start(PcmSink sink);

    /** Остановить захват. После вызова {@code sink} больше не вызывается. */
    void stop();

    /** Приёмник планарного стерео Float32 PCM (L и R одной длины). */
    interface PcmSink {
        void onPcm(float[] left, float[] right, int sampleRate);
    }
}
