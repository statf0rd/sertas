package dev.sertas.engine;

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ScreenAudioLoopbackTest {

    @Test
    void tonePushedBySenderReachesReceiverSink() throws Exception {
        WebRtcEngine engine = WebRtcEngine.headless();
        PeerSession[] holder = new PeerSession[2];
        CountDownLatch heardNonSilence = new CountDownLatch(1);

        PeerSession a = new PeerSession(engine, new PeerSession.Signals() {
            @Override
            public void onLocalDescription(RTCSessionDescription d) {
                holder[1].onRemoteDescription(d);
            }

            @Override
            public void onLocalIceCandidate(RTCIceCandidate c) {
                holder[1].onRemoteIceCandidate(c);
            }
        });

        PeerSession b = new PeerSession(engine, new PeerSession.Signals() {
            @Override
            public void onLocalDescription(RTCSessionDescription d) {
                holder[0].onRemoteDescription(d);
            }

            @Override
            public void onLocalIceCandidate(RTCIceCandidate c) {
                holder[0].onRemoteIceCandidate(c);
            }

            @Override
            public void onTrack(RTCRtpTransceiver transceiver) {
                MediaStreamTrack track = transceiver.getReceiver().getTrack();
                if (track instanceof AudioTrack audio) {
                    audio.addSink(new AudioTrackSink() {
                        @Override
                        public void onData(byte[] data, int bitsPerSample, int sampleRate,
                                           int channels, int frames) {
                            for (byte sample : data) {
                                if (sample != 0) {
                                    heardNonSilence.countDown();
                                    return;
                                }
                            }
                        }
                    });
                }
            }
        });

        holder[0] = a;
        holder[1] = b;

        SystemAudioTrack sender = new SystemAudioTrack(engine);
        a.addTrack(sender.track());
        a.createOffer();

        // Дать соединению установиться, затем включить синус.
        Thread.sleep(3_000);
        sender.start(new FakeSystemAudioProvider(440));

        assertTrue(heardNonSilence.await(25, TimeUnit.SECONDS),
                "приёмник не получил ненулевой звук — путь передачи демо-звука не работает");

        sender.stop();
        a.close();
        b.close();
        engine.dispose();
    }
}
