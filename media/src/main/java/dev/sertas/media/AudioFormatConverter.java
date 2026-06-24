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
