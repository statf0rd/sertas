package dev.sertas.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SoftwareMixerTest {

    @Test
    void sumsTwoSourcesWithPerSourceGain() {
        SoftwareMixer m = new SoftwareMixer();
        m.addSource("a");
        m.addSource("b");
        m.setGain("a", 1.0f);
        m.setGain("b", 0.5f);
        m.submit("a", new float[]{0.2f, 0.2f, 0.4f, 0.4f}); // 2 кадра стерео
        m.submit("b", new float[]{0.2f, 0.2f, 0.2f, 0.2f});

        float[] out = new float[4];
        assertEquals(2, m.pull(out, 2));
        // кадр0: 0.2*1 + 0.2*0.5 = 0.3 ; кадр1: 0.4 + 0.1 = 0.5
        assertEquals(0.3f, out[0], 1e-6f);
        assertEquals(0.3f, out[1], 1e-6f);
        assertEquals(0.5f, out[2], 1e-6f);
        assertEquals(0.5f, out[3], 1e-6f);
    }

    @Test
    void clipsToUnitRange() {
        SoftwareMixer m = new SoftwareMixer();
        m.addSource("a");
        m.submit("a", new float[]{2.0f, -2.0f});
        float[] out = new float[2];
        m.pull(out, 1);
        assertEquals(1f, out[0]);
        assertEquals(-1f, out[1]);
    }

    @Test
    void underrunYieldsSilence() {
        SoftwareMixer m = new SoftwareMixer();
        m.addSource("a");
        m.submit("a", new float[]{0.5f, 0.5f}); // только 1 кадр
        float[] out = new float[4];
        assertEquals(2, m.pull(out, 2));
        assertEquals(0.5f, out[0], 1e-6f);
        assertEquals(0f, out[2]);
        assertEquals(0f, out[3]);
    }

    @Test
    void gainZeroMutesSource() {
        SoftwareMixer m = new SoftwareMixer();
        m.addSource("a");
        m.setGain("a", 0f);
        m.submit("a", new float[]{0.9f, 0.9f});
        float[] out = new float[2];
        m.pull(out, 1);
        assertEquals(0f, out[0]);
    }

    @Test
    void defaultGainIsOne() {
        SoftwareMixer m = new SoftwareMixer();
        m.addSource("a");
        m.submit("a", new float[]{0.5f, 0.5f});
        assertEquals(1f, m.gain("a"));
        float[] out = new float[2];
        m.pull(out, 1);
        assertEquals(0.5f, out[0], 1e-6f);
    }

    @Test
    void negativeGainClampedToZero() {
        SoftwareMixer m = new SoftwareMixer();
        m.addSource("a");
        m.setGain("a", -3f);
        assertEquals(0f, m.gain("a"));
    }

    @Test
    void removedSourceDoesNotContribute() {
        SoftwareMixer m = new SoftwareMixer();
        m.addSource("a");
        m.submit("a", new float[]{0.5f, 0.5f});
        m.removeSource("a");
        float[] out = new float[2];
        m.pull(out, 1);
        assertEquals(0f, out[0]);
        assertFalse(m.hasSource("a"));
    }

    @Test
    void submitToUnknownSourceIsIgnored() {
        SoftwareMixer m = new SoftwareMixer();
        assertDoesNotThrow(() -> m.submit("ghost", new float[]{1f, 1f}));
        float[] out = new float[2];
        m.pull(out, 1);
        assertEquals(0f, out[0]);
    }

    @Test
    void consumesAcrossPullsInOrder() {
        SoftwareMixer m = new SoftwareMixer();
        m.addSource("a");
        m.submit("a", new float[]{0.1f, 0.1f, 0.2f, 0.2f}); // 2 кадра
        float[] out = new float[2];
        m.pull(out, 1);
        assertEquals(0.1f, out[0], 1e-6f);
        m.pull(out, 1);
        assertEquals(0.2f, out[0], 1e-6f); // второй кадр
        m.pull(out, 1);
        assertEquals(0f, out[0]); // исчерпан
    }
}
