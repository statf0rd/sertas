package dev.sertas.engine;

import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioSource;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.sertas.media.AudioFormatConverter;

import java.util.Arrays;

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

    private final SoftwareMixer mixer = new SoftwareMixer();
    private float[] scratch = new float[0]; // только аудио-поток воспроизведения

    /** Вид удалённого трека по его id (метка трека отправителя доходит через a=msid). */
    public static Kind kindOf(MediaStreamTrack track) {
        return SystemAudioTrack.LABEL.equals(track.getId()) ? Kind.DEMO : Kind.VOICE;
    }

    public static String sourceId(String peerId, Kind kind) {
        return peerId + ":" + kind;
    }

    /** Подключить удалённый аудио-трек: добавить источник и sink в микшер. */
    public void attach(String peerId, AudioTrack track) {
        Kind kind = kindOf(track);
        String id = sourceId(peerId, kind);
        mixer.addSource(id);
        track.addSink((data, bitsPerSample, sampleRate, channels, frames) -> {
            // Удалённый Opus декодируется в 48к; иные частоты пока не ресэмплим.
            if (bitsPerSample == 16 && sampleRate == 48_000 && frames > 0) {
                float[] stereo = AudioFormatConverter.s16InterleavedToFloatStereo(data, frames, channels);
                mixer.submit(id, stereo);
            }
        });
    }

    /** Отключить участника (оба его источника). */
    public void detach(String peerId) {
        mixer.removeSource(sourceId(peerId, Kind.VOICE));
        mixer.removeSource(sourceId(peerId, Kind.DEMO));
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
        byte[] pcm = AudioFormatConverter.floatStereoToS16Interleaved(scratch, nSamples, nChannels);
        System.arraycopy(pcm, 0, audioSamples, 0, Math.min(pcm.length, audioSamples.length));
        return nSamples;
    }
}
