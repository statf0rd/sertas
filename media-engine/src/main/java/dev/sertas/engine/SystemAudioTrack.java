package dev.sertas.engine;

import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.sertas.engine.SystemAudioProvider.PcmSink;
import dev.sertas.media.AudioFormatConverter;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Трек звука демонстрации: владеет {@link CustomAudioSource} + {@link AudioTrack}
 * с меткой {@value #LABEL}. Захват (поток провайдера) только копит 10мс-блоки в
 * очередь; отдельный планировщик пушит РОВНО ОДИН блок каждые 10мс в источник.
 *
 * <p>Почему так: {@code CustomAudioSource.pushAudio} нужно звать с единственного
 * потока строго раз в 10мс (как в гайде webrtc-java). Тайтовый/бёрстовый push из
 * потока захвата ловит гонку в нативном {@code audio_send_stream}
 * ({@code RUNS_SERIALIZED}) → фатальный краш. В паузах досылаем тишину для ритма
 * (WASAPI loopback при цифровой тишине не отдаёт буферов).
 *
 * <p>Звук демонстрации идёт мимо APM — стерео-музыкальный профиль навешивается
 * SDP-munging'ом по метке трека (см. {@code MeshCoordinator}).
 */
public final class SystemAudioTrack implements PcmSink {

    public static final String LABEL = "screen-audio";

    private static final int CHANNELS = 2;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int MAX_PENDING = 50; // ~500мс — защита от роста при бёрсте

    private final CustomAudioSource source = new CustomAudioSource();
    private final AudioTrack track;

    /** Готовые 10мс-блоки от захвата → планировщик. */
    private final ConcurrentLinkedQueue<Pcm10msReframer.Block> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCount = new AtomicInteger();

    private final ScheduledExecutorService pusher =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "screen-audio-push");
                t.setDaemon(true);
                return t;
            });
    private ScheduledFuture<?> pushTask;

    // Рефреймер под частоту источника (Mac SCStream — 48к; Windows WASAPI — частота
    // устройства, бывает 44.1к). Пересоздаётся при смене частоты. Только поток захвата.
    private Pcm10msReframer reframer;
    private int reframerRate;
    private volatile int sampleRate = 48_000;
    private volatile int framesPerBlock = 480;

    private SystemAudioProvider provider;
    private long dbgPcm, dbgData, dbgSilence; // диагностика

    public SystemAudioTrack(WebRtcEngine engine) {
        this.track = engine.createAudioTrack(LABEL, source);
        this.track.setEnabled(false);
    }

    /** Трек для добавления в меш (вызывать до {@link #start}). */
    public AudioTrack track() {
        return track;
    }

    /** Включить: открыть трек, запустить 10мс-планировщик пуша и провайдер захвата. */
    public synchronized void start(SystemAudioProvider provider) {
        this.provider = provider;
        track.setEnabled(true);
        if (pushTask == null) {
            pushTask = pusher.scheduleAtFixedRate(this::pushTick, 0, 10, TimeUnit.MILLISECONDS);
        }
        provider.start(this);
    }

    /** Выключить: остановить захват, планировщик и закрыть трек. */
    public synchronized void stop() {
        if (provider != null) {
            provider.stop();
            provider = null;
        }
        if (pushTask != null) {
            pushTask.cancel(false);
            pushTask = null;
        }
        pending.clear();
        pendingCount.set(0);
        track.setEnabled(false);
    }

    /** Поток захвата: только копим блоки (без push). */
    @Override
    public void onPcm(float[] left, float[] right, int sourceSampleRate) {
        if (reframer == null || reframerRate != sourceSampleRate) {
            reframer = new Pcm10msReframer(sourceSampleRate);
            reframerRate = sourceSampleRate;
            sampleRate = sourceSampleRate;
            framesPerBlock = sourceSampleRate / 100;
        }
        if (dbgPcm++ == 0 || dbgPcm % 200 == 0) {
            System.err.println("[demo] capture onPcm #" + dbgPcm + " rate=" + sourceSampleRate
                    + " frames=" + left.length);
        }
        for (Pcm10msReframer.Block b : reframer.offer(left, right)) {
            if (pendingCount.get() < MAX_PENDING) {
                pending.add(b);
                pendingCount.incrementAndGet();
            }
        }
    }

    /** Единственный поток push: ровно один 10мс-кадр (данные или тишина) каждые 10мс. */
    private void pushTick() {
        try {
            Pcm10msReframer.Block b = pending.poll();
            byte[] pcm;
            int frames;
            if (b != null) {
                pendingCount.decrementAndGet();
                pcm = AudioFormatConverter.float32PlanarToS16Interleaved(b.left(), b.right());
                frames = b.left().length;
                dbgData++;
            } else {
                frames = framesPerBlock;
                pcm = AudioFormatConverter.silenceFrame(frames, CHANNELS);
                dbgSilence++;
            }
            if ((dbgData + dbgSilence) % 500 == 1) {
                System.err.println("[demo] push: data=" + dbgData + " silence=" + dbgSilence
                        + " rate=" + sampleRate);
            }
            source.pushAudio(pcm, BITS_PER_SAMPLE, sampleRate, CHANNELS, frames);
        } catch (RuntimeException ignored) {
            // не даём исключению убить планировщик
        }
    }

    /**
     * Освободить ресурсы. Нативная очистка трека/источника происходит при
     * {@code WebRtcEngine.dispose()} (factory.dispose) — отдельный
     * {@code source.dispose()} здесь не вызываем, иначе «reference still around».
     */
    public void dispose() {
        stop();
        pusher.shutdownNow();
    }
}
