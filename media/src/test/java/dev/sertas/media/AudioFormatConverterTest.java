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

    private static short le16(byte[] a, int i) {
        return (short) ((a[i] & 0xFF) | (a[i + 1] << 8));
    }
}
