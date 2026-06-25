package dev.sertas.engine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Pcm10msReframerTest {

    @Test
    void blockSizeIs480At48k() {
        assertEquals(480, new Pcm10msReframer(48_000).framesPerBlock());
    }

    @Test
    void emitsFullBlocksAndKeepsRemainder() {
        Pcm10msReframer r = new Pcm10msReframer(48_000);
        List<Pcm10msReframer.Block> first = r.offer(ramp(700), ramp(700));
        assertEquals(1, first.size());
        assertEquals(480, first.get(0).left().length);
        assertEquals(480, first.get(0).right().length);

        // 220 в остатке + 300 = 520 → ещё один блок, 40 в остатке
        List<Pcm10msReframer.Block> second = r.offer(ramp(300), ramp(300));
        assertEquals(1, second.size());
        assertEquals(480, second.get(0).left().length);
    }

    @Test
    void exactBlockEmitsOneAndNoRemainder() {
        Pcm10msReframer r = new Pcm10msReframer(48_000);
        assertEquals(1, r.offer(new float[480], new float[480]).size());
        assertEquals(0, r.offer(new float[0], new float[0]).size());
    }

    @Test
    void partialChunkEmitsNothing() {
        Pcm10msReframer r = new Pcm10msReframer(48_000);
        assertTrue(r.offer(new float[100], new float[100]).isEmpty());
    }

    @Test
    void preservesSampleOrderAcrossBlocks() {
        Pcm10msReframer r = new Pcm10msReframer(48_000);
        List<Pcm10msReframer.Block> blocks = r.offer(ramp(960), ramp(960));
        assertEquals(2, blocks.size());
        assertEquals(0f, blocks.get(0).left()[0]);
        assertEquals(479f, blocks.get(0).left()[479]);
        assertEquals(480f, blocks.get(1).left()[0]);
        assertEquals(959f, blocks.get(1).left()[479]);
    }

    @Test
    void rejectsMismatchedChannelLengths() {
        assertThrows(IllegalArgumentException.class,
                () -> new Pcm10msReframer(48_000).offer(new float[10], new float[11]));
    }

    private static float[] ramp(int n) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        return a;
    }
}
