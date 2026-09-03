package dev.sertas.engine;

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSink;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ScreenAudioLoopbackTest {

    @Test
    void tonePushedBySenderReachesReceiverSink() throws Exception {
        WebRtcEngine sendEngine = WebRtcEngine.pushOnly(); // отправитель: ADM без захвата
        WebRtcEngine engine = WebRtcEngine.headless();     // приёмник: виртуальный playout
        PeerSession[] holder = new PeerSession[2];
        // Два движка = два набора нативных потоков. Кросс-вызовы между PC делаем
        // с отдельного потока: прямой вызов из колбэка одного движка в другой
        // (и обратно) — взаимное блокирующее Invoke → deadlock.
        ExecutorService sig = Executors.newSingleThreadExecutor();
        CountDownLatch heardNonSilence = new CountDownLatch(1);

        PeerSession a = new PeerSession(sendEngine, new PeerSession.Signals() {
            @Override
            public void onLocalDescription(RTCSessionDescription d) {
                sig.execute(() -> holder[1].onRemoteDescription(d));
            }

            @Override
            public void onLocalIceCandidate(RTCIceCandidate c) {
                sig.execute(() -> holder[1].onRemoteIceCandidate(c));
            }
        });

        PeerSession b = new PeerSession(engine, new PeerSession.Signals() {
            @Override
            public void onLocalDescription(RTCSessionDescription d) {
                sig.execute(() -> holder[0].onRemoteDescription(d));
            }

            @Override
            public void onLocalIceCandidate(RTCIceCandidate c) {
                sig.execute(() -> holder[0].onRemoteIceCandidate(c));
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

        SystemAudioTrack sender = new SystemAudioTrack(sendEngine);
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
        sendEngine.dispose();
        sig.shutdownNow();
    }
}
