package dev.sertas.media;

/**
 * Конвертация захваченного системного аудио в формат, который ждёт
 * {@code CustomAudioSource.pushAudio}: 16-бит signed, interleaved, little-endian.
 *
 * <p>ScreenCaptureKit / Core Audio отдают Float32 planar (отдельные буферы
 * каналов), WASAPI обычно уже interleaved — этот класс приводит planar-вход к
 * целевому формату и умеет отдавать кадр тишины для удержания 10мс-ритма
 * (loopback молчит при цифровой тишине).
 */
public final class AudioFormatConverter {

    private AudioFormatConverter() {}

    /**
     * Стерео planar Float32 [-1, 1] &rarr; interleaved S16LE.
     *
     * @param left  семплы левого канала
     * @param right семплы правого канала (та же длина, что left)
     * @return байты длиной {@code left.length * 2 channels * 2 bytes}
     */
    public static byte[] float32PlanarToS16Interleaved(float[] left, float[] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("channel length mismatch: " + left.length + " vs " + right.length);
        }
        int frames = left.length;
        byte[] out = new byte[frames * 2 * 2];
        int o = 0;
        for (int i = 0; i < frames; i++) {
            o = writeSample(out, o, left[i]);
            o = writeSample(out, o, right[i]);
        }
        return out;
    }

    /** Кадр тишины: {@code frameCount * channels * 2} нулевых байт. */
    public static byte[] silenceFrame(int frameCount, int channels) {
        return new byte[frameCount * channels * 2];
    }

    /**
     * S16LE interleaved (декодированный удалённый трек) &rarr; float interleaved
     * стерео [-1, 1] для микшера. Моно дублируется в оба канала; стерео — как есть
     * (лишние каналы свыше 2 игнорируются).
     *
     * @param s16      байты, длиной не менее {@code frames * channels * 2}
     * @param frames   число кадров
     * @param channels каналов во входе (1 = моно, 2+ = берём первые два)
     */
    public static float[] s16InterleavedToFloatStereo(byte[] s16, int frames, int channels) {
        float[] out = new float[frames * 2];
        int o = 0;
        for (int f = 0; f < frames; f++) {
            int base = f * channels * 2;
            float l = readSample(s16, base);
            float r = channels == 1 ? l : readSample(s16, base + 2);
            out[o++] = l;
            out[o++] = r;
        }
        return out;
    }

    /**
     * Float interleaved стерео [-1, 1] (выход микшера) &rarr; S16LE interleaved для
     * воспроизведения. {@code outChannels==1} — даунмикс (среднее L/R); 2 — как есть.
     *
     * @return байты длиной {@code frames * outChannels * 2}
     */
    public static byte[] floatStereoToS16Interleaved(float[] stereo, int frames, int outChannels) {
        byte[] out = new byte[frames * outChannels * 2];
        int o = 0;
        for (int f = 0; f < frames; f++) {
            float l = stereo[f * 2];
            float r = stereo[f * 2 + 1];
            if (outChannels == 1) {
                o = writeSample(out, o, (l + r) * 0.5f);
            } else {
                o = writeSample(out, o, l);
                o = writeSample(out, o, r);
            }
        }
        return out;
    }

    /** Прочитать S16LE-сэмпл из {@code b[i..i+1]} как float [-1, 1). */
    private static float readSample(byte[] b, int i) {
        short s = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
        return s / 32768f;
    }

    private static int writeSample(byte[] out, int o, float v) {
        float c = v < -1f ? -1f : (v > 1f ? 1f : v);
        int s = Math.round(c >= 0 ? c * 32767f : c * 32768f);
        if (s > 32767) {
            s = 32767;
        } else if (s < -32768) {
            s = -32768;
        }
        out[o] = (byte) (s & 0xFF);
        out[o + 1] = (byte) ((s >> 8) & 0xFF);
        return o + 2;
    }
}
