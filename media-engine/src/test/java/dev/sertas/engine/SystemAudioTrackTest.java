package dev.sertas.engine;

import dev.onvoid.webrtc.media.MediaStreamTrack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SystemAudioTrackTest {

    @Test
    void createsDisabledAudioTrackLabeledScreenAudio() {
        WebRtcEngine engine = WebRtcEngine.headless();
        try {
            SystemAudioTrack sat = new SystemAudioTrack(engine);
            assertEquals(MediaStreamTrack.AUDIO_TRACK_KIND, sat.track().getKind());
            assertFalse(sat.track().isEnabled(), "до start трек выключен");
        } finally {
            engine.dispose();
        }
    }

    @Test
    void pushingExactBlockDoesNotThrow() {
        WebRtcEngine engine = WebRtcEngine.headless();
        try {
            SystemAudioTrack sat = new SystemAudioTrack(engine);
            // Ровно 480 фреймов → один pushAudio в нативный CustomAudioSource.
            assertDoesNotThrow(() -> sat.onPcm(new float[480], new float[480], 48_000));
        } finally {
            engine.dispose();
        }
    }

    @Test
    void startEnablesAndStopDisablesTrack() {
        WebRtcEngine engine = WebRtcEngine.headless();
        try {
            SystemAudioTrack sat = new SystemAudioTrack(engine);
            sat.start(new FakeSystemAudioProvider(0)); // тишина, чтобы не шуметь
            assertTrue(sat.track().isEnabled());
            sat.stop();
            assertFalse(sat.track().isEnabled());
        } finally {
            engine.dispose();
        }
    }
}
