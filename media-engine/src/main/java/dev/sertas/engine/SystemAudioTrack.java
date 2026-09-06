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
 * ({@code RUNS_SERIALIZED}) → фатальный краш. Что пушить на тике (данные /
 * пропуск / тишина) решает {@link PushPacer}: тишина досылается только при
 * длительном голоде источника, а не на каждом пустом тике — иначе за реальное
 * время уходит больше 100 кадров/с и у зрителя слышен треск.
 *
 * <p>Звук демонстрации идёт мимо APM — стерео-музыкальный профиль навешивается
 * SDP-munging'ом по метке трека (см. {@code MeshCoordinator}).
 */
public final class SystemAudioTrack implements PcmSink {

    public static final String LABEL = "screen-audio";

    private static final int CHANNELS = 2;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int MAX_PENDING = 50; // ~500мс — защита от роста при бёрсте
    private static final int PRIME_BLOCKS = 4;   // 40мс праймер — покрывает джиттер таймера Windows (15.6мс)
    private static final int STARVED_TICKS = 50; // 500мс без данных → тишина для ритма

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
    private final PushPacer pacer = new PushPacer(PRIME_BLOCKS, STARVED_TICKS); // только поток push
    private long dbgPcm, dbgData, dbgSilence, dbgSkip; // диагностика

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
        pacer.reset();
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

    /** Единственный поток push: не больше одного 10мс-кадра за тик (см. {@link PushPacer}). */
    private void pushTick() {
        try {
            PushPacer.Action action = pacer.tick(pendingCount.get());
            Pcm10msReframer.Block b = action == PushPacer.Action.DATA ? pending.poll() : null;
            byte[] pcm;
            int frames;
            if (b != null) {
                pendingCount.decrementAndGet();
                pcm = AudioFormatConverter.float32PlanarToS16Interleaved(b.left(), b.right());
                frames = b.left().length;
                dbgData++;
            } else if (action == PushPacer.Action.SILENCE) {
                frames = framesPerBlock;
                pcm = AudioFormatConverter.silenceFrame(frames, CHANNELS);
                dbgSilence++;
            } else {
                dbgSkip++;
                return; // пустой тик — ничего не шлём, NetEq у зрителя скроет дырку
            }
            if ((dbgData + dbgSilence) % 500 == 1) {
                System.err.println("[demo] push: data=" + dbgData + " silence=" + dbgSilence
                        + " skip=" + dbgSkip + " rate=" + sampleRate);
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
