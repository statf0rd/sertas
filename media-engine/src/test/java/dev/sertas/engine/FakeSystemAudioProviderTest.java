package dev.sertas.engine;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FakeSystemAudioProviderTest {

    @Test
    void emitsPlanarStereoChunksUntilStopped() throws Exception {
        FakeSystemAudioProvider p = new FakeSystemAudioProvider(440);
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean badShape = new AtomicBoolean();
        AtomicBoolean sawNonZero = new AtomicBoolean();

        p.start((left, right, sampleRate) -> {
            if (left.length != 480 || right.length != 480 || sampleRate != 48_000) {
                badShape.set(true);
            }
            for (float v : left) {
                if (v != 0f) {
                    sawNonZero.set(true);
                }
            }
            calls.incrementAndGet();
        });

        Thread.sleep(150);
        p.stop();
        int afterStop = calls.get();
        Thread.sleep(80);

        assertTrue(afterStop >= 3, "ожидали ≥3 чанка за 150мс, получили " + afterStop);
        assertFalse(badShape.get(), "неверная форма чанка");
        assertTrue(sawNonZero.get(), "синус 440Гц не должен быть тишиной");
        assertEquals(afterStop, calls.get(), "после stop() чанки не должны приходить");
    }

    @Test
    void zeroFrequencyIsSilence() throws Exception {
        FakeSystemAudioProvider p = new FakeSystemAudioProvider(0);
        AtomicReference<Boolean> nonZero = new AtomicReference<>(false);
        p.start((left, right, sr) -> {
            for (float v : left) {
                if (v != 0f) {
                    nonZero.set(true);
                }
            }
        });
        Thread.sleep(60);
        p.stop();
        assertFalse(nonZero.get(), "частота 0 → тишина");
    }
}
