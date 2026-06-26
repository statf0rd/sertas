package dev.sertas.engine;

import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioSource;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;
import dev.sertas.media.AudioFormatConverter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Сторона слушателя: принимает удалённые аудио-треки, различает источники
 * (голос участника / звук его демо) и сводит их в {@link SoftwareMixer} с
 * независимой громкостью на источник. Вход каждого трека — {@code AudioTrackSink}
 * с декодированным PCM (S16) → конвертация в канонический 48к стерео float → микшер.
 *
 * <p>Реализует {@link AudioSource}: ADM тянет финальный микс через
 * {@link #onPlaybackData} (заменяя штатный микшер libwebrtc) —
 * {@code engine.audioDeviceModule().setAudioSource(mixer)}.
 */
public final class RemoteAudioMixer implements AudioSource {

    /** Вид источника: голос (микрофон) или звук демонстрации. */
    public enum Kind { VOICE, DEMO }

    /** Привязка sink к треку, чтобы снять его при detach (иначе утечка). */
    private record Attached(AudioTrack track, AudioTrackSink sink) {}

    private final SoftwareMixer mixer = new SoftwareMixer();
    private final Map<String, Attached> attached = new HashMap<>(); // только FX-поток (attach/detach)
    private float[] scratch = new float[0];   // только аудио-поток воспроизведения

    // Диагностика (-Dsertas.mixer=on): логируем первое срабатывание, чтобы понять
    // на железе, тянется ли наш playout и в каком формате приходит удалённый звук.
    private volatile boolean loggedPlayout = false;
    private final java.util.Set<String> loggedSinks = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Вид удалённого трека по его id (метка трека отправителя доходит через a=msid). */
    public static Kind kindOf(MediaStreamTrack track) {
        return SystemAudioTrack.LABEL.equals(track.getId()) ? Kind.DEMO : Kind.VOICE;
    }

    public static String sourceId(String peerId, Kind kind) {
        return peerId + ":" + kind;
    }

    /** Подключить удалённый аудио-трек: добавить источник и sink в микшер. Идемпотентно. */
    public void attach(String peerId, AudioTrack track) {
        Kind kind = kindOf(track);
        String id = sourceId(peerId, kind);
        if (attached.containsKey(id)) {
            return; // уже подключён — без дублирующего sink
        }
        mixer.addSource(id);
        AudioTrackSink sink = (data, bitsPerSample, sampleRate, channels, frames) -> {
            if (loggedSinks.add(id)) {
                System.err.println("[mixer] sink first onData: source=" + id + " bitsPerSample="
                        + bitsPerSample + " rate=" + sampleRate + " channels=" + channels + " frames=" + frames);
            }
            // Удалённый Opus декодируется в 48к; иные частоты пока не ресэмплим.
            if (bitsPerSample == 16 && sampleRate == 48_000 && frames > 0) {
                mixer.submit(id, AudioFormatConverter.s16InterleavedToFloatStereo(data, frames, channels));
            }
        };
        attached.put(id, new Attached(track, sink));
        track.addSink(sink);
    }

    /** Отключить участника: снять sink'и с треков и убрать источники. */
    public void detach(String peerId) {
        for (Kind kind : Kind.values()) {
            String id = sourceId(peerId, kind);
            Attached a = attached.remove(id);
            if (a != null) {
                a.track().removeSink(a.sink());
            }
            mixer.removeSource(id);
        }
    }

    /** Установить громкость источника (0..N). */
    public void setGain(String peerId, Kind kind, float gain) {
        mixer.setGain(sourceId(peerId, kind), gain);
    }

    public boolean hasSource(String peerId, Kind kind) {
        return mixer.hasSource(sourceId(peerId, kind));
    }

    /** Слить текущий микс (48к стерео float, {@code frames*2}) для воспроизведения. */
    public int pull(float[] out, int frames) {
        return mixer.pull(out, frames);
    }

    /**
     * ADM тянет финальный микс для воспроизведения. Зовётся на аудио-потоке с
     * фиксированным ритмом; не блокирует и не аллоцирует сверх переиспользуемого
     * scratch. Недостача данных → тишина (возвращаем запрошенные {@code nSamples}).
     *
     * @param audioSamples   буфер на {@code nSamples*nChannels*nBytesPerSample} байт — заполняем
     * @param nSamples       число кадров
     * @param nBytesPerSample байт на сэмпл (2 = 16-бит)
     * @param nChannels      каналов вывода
     * @param samplesPerSec  частота вывода (микс — 48кГц; иные пока не ресэмплим)
     */
    @Override
    public int onPlaybackData(byte[] audioSamples, int nSamples, int nBytesPerSample,
                              int nChannels, int samplesPerSec) {
        if (nBytesPerSample != 2 || nSamples <= 0) {
            Arrays.fill(audioSamples, (byte) 0);
            return nSamples;
        }
        if (scratch.length < nSamples * 2) {
            scratch = new float[nSamples * 2];
        }
        pull(scratch, nSamples);
        if (!loggedPlayout) {
            loggedPlayout = true;
            boolean silent = true;
            for (int i = 0; i < nSamples * 2; i++) {
                if (scratch[i] != 0f) {
                    silent = false;
                    break;
                }
            }
            System.err.println("[mixer] onPlaybackData first call: nSamples=" + nSamples
                    + " bytesPerSample=" + nBytesPerSample + " channels=" + nChannels
                    + " rate=" + samplesPerSec + " mixSilent=" + silent);
        }
        // Пишем S16 прямо в буфер ADM — без аллокации на аудио-потоке.
        AudioFormatConverter.floatStereoToS16InterleavedInto(audioSamples, scratch, nSamples, nChannels);
        return nSamples;
    }
}
