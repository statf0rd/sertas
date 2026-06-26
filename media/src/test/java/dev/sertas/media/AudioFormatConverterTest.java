package dev.sertas.media;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AudioFormatConverterTest {

    @Test
    void interleavesAndScalesToS16LE() {
        float[] left = {0f, 1f};
        float[] right = {-1f, 0.5f};
        byte[] out = AudioFormatConverter.float32PlanarToS16Interleaved(left, right);
        // 2 кадра * 2 канала * 2 байта
        assertEquals(8, out.length);
        assertEquals((short) 0, le16(out, 0));        // L=0
        assertEquals((short) -32768, le16(out, 2));   // R=-1
        assertEquals((short) 32767, le16(out, 4));    // L=1
        assertEquals((short) 16384, le16(out, 6));    // R=0.5 -> round(0.5*32767)=16384
    }

    @Test
    void clampsOutOfRange() {
        byte[] out = AudioFormatConverter.float32PlanarToS16Interleaved(new float[]{2f}, new float[]{-2f});
        assertEquals((short) 32767, le16(out, 0));
        assertEquals((short) -32768, le16(out, 2));
    }

    @Test
    void silenceFrameIsZeroedAndCorrectSize() {
        byte[] s = AudioFormatConverter.silenceFrame(480, 2);
        assertEquals(480 * 2 * 2, s.length);
        for (byte b : s) {
            assertEquals(0, b);
        }
    }

    @Test
    void rejectsMismatchedChannelLengths() {
        assertThrows(IllegalArgumentException.class,
                () -> AudioFormatConverter.float32PlanarToS16Interleaved(new float[]{0f}, new float[]{0f, 0f}));
    }

    @Test
    void s16MonoUpmixedToFloatStereo() {
        byte[] s16 = new byte[4];
        putLe16(s16, 0, (short) 16384);  // кадр0
        putLe16(s16, 2, (short) -32768); // кадр1
        float[] out = AudioFormatConverter.s16InterleavedToFloatStereo(s16, 2, 1);
        assertEquals(4, out.length);
        assertEquals(0.5f, out[0], 1e-6f);  // L=R дубль
        assertEquals(0.5f, out[1], 1e-6f);
        assertEquals(-1f, out[2], 1e-6f);
        assertEquals(-1f, out[3], 1e-6f);
    }

    @Test
    void s16StereoToFloatStereoKeepsChannels() {
        byte[] s16 = new byte[4];
        putLe16(s16, 0, (short) 16384);  // L
        putLe16(s16, 2, (short) -16384); // R
        float[] out = AudioFormatConverter.s16InterleavedToFloatStereo(s16, 1, 2);
        assertEquals(0.5f, out[0], 1e-6f);
        assertEquals(-0.5f, out[1], 1e-6f);
    }

    @Test
    void floatStereoToS16StereoRoundTrips() {
        byte[] out = AudioFormatConverter.floatStereoToS16Interleaved(new float[]{1f, -1f}, 1, 2);
        assertEquals(4, out.length);
        assertEquals((short) 32767, le16(out, 0));
        assertEquals((short) -32768, le16(out, 2));
    }

    @Test
    void floatStereoDownmixedToS16Mono() {
        byte[] out = AudioFormatConverter.floatStereoToS16Interleaved(new float[]{0.5f, -0.5f}, 1, 1);
        assertEquals(2, out.length);
        assertEquals((short) 0, le16(out, 0)); // (0.5 + -0.5)/2 = 0
    }

    private static short le16(byte[] a, int i) {
        return (short) ((a[i] & 0xFF) | (a[i + 1] << 8));
    }

    private static void putLe16(byte[] a, int i, short s) {
        a[i] = (byte) (s & 0xFF);
        a[i + 1] = (byte) ((s >> 8) & 0xFF);
    }
}
