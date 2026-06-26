package dev.sertas.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RemoteAudioMixerPlayoutTest {

    @Test
    void emptyMixYieldsSilenceAndReturnsRequestedFrames() {
        RemoteAudioMixer mixer = new RemoteAudioMixer();
        byte[] buf = new byte[480 * 2 * 2]; // 480 кадров стерео 16-бит
        for (int i = 0; i < buf.length; i++) {
            buf[i] = 7; // мусор, чтобы убедиться, что обнулили
        }
        int n = mixer.onPlaybackData(buf, 480, 2, 2, 48_000);
        assertEquals(480, n);
        for (byte b : buf) {
            assertEquals(0, b);
        }
    }

    @Test
    void unsupportedSampleSizeYieldsSilence() {
        RemoteAudioMixer mixer = new RemoteAudioMixer();
        byte[] buf = new byte[480];
        for (int i = 0; i < buf.length; i++) {
            buf[i] = 5;
        }
        int n = mixer.onPlaybackData(buf, 480, 1, 1, 48_000); // 1 байт/сэмпл не поддержан
        assertEquals(480, n);
        for (byte b : buf) {
            assertEquals(0, b);
        }
    }
}
