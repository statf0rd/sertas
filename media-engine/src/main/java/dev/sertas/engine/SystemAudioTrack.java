package dev.sertas.engine;

import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.sertas.engine.SystemAudioProvider.PcmSink;
import dev.sertas.media.AudioFormatConverter;

/**
 * Трек звука демонстрации: владеет {@link CustomAudioSource} + {@link AudioTrack}
 * с меткой {@value #LABEL}. Принимает планарный Float32 от провайдера, ре-фреймит
 * в 10мс ({@link Pcm10msReframer}), конвертирует в S16 interleaved
 * ({@link AudioFormatConverter}) и пушит в источник. До {@link #start} трек выключен.
 *
 * <p>Звук демонстрации идёт мимо APM (без шумодава/AGC) — стерео-музыкальный
 * профиль навешивается SDP-munging'ом по метке трека (см. {@code MeshCoordinator}).
 */
public final class SystemAudioTrack implements PcmSink {

    public static final String LABEL = "screen-audio";

    private static final int CHANNELS = 2;
    private static final int BITS_PER_SAMPLE = 16;

    private final CustomAudioSource source = new CustomAudioSource();
    private final AudioTrack track;

    // Рефреймер под частоту источника (Mac SCStream — 48к; Windows WASAPI — частота
    // устройства, бывает 44.1к). Пересоздаётся при смене частоты. Только поток захвата.
    private Pcm10msReframer reframer;
    private int reframerRate;

    private SystemAudioProvider provider;

    public SystemAudioTrack(WebRtcEngine engine) {
        this.track = engine.createAudioTrack(LABEL, source);
        this.track.setEnabled(false);
    }

    /** Трек для добавления в меш (вызывать до {@link #start}). */
    public AudioTrack track() {
        return track;
    }

    /** Включить: открыть трек и запустить провайдер захвата. */
    public synchronized void start(SystemAudioProvider provider) {
        this.provider = provider;
        track.setEnabled(true);
        provider.start(this);
    }

    /** Выключить: остановить провайдер и закрыть трек. */
    public synchronized void stop() {
        if (provider != null) {
            provider.stop();
            provider = null;
        }
        track.setEnabled(false);
    }

    @Override
    public void onPcm(float[] left, float[] right, int sampleRate) {
        if (reframer == null || reframerRate != sampleRate) {
            reframer = new Pcm10msReframer(sampleRate);
            reframerRate = sampleRate;
        }
        for (Pcm10msReframer.Block b : reframer.offer(left, right)) {
            byte[] pcm = AudioFormatConverter.float32PlanarToS16Interleaved(b.left(), b.right());
            source.pushAudio(pcm, BITS_PER_SAMPLE, sampleRate, CHANNELS, b.left().length);
        }
    }

    /**
     * Освободить ресурсы. Нативная очистка трека/источника происходит при
     * {@code WebRtcEngine.dispose()} (factory.dispose) — отдельный
     * {@code source.dispose()} здесь не вызываем, иначе «reference still around».
     */
    public void dispose() {
        stop();
    }
}
