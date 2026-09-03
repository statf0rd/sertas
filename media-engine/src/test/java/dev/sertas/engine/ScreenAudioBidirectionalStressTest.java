package dev.sertas.engine;

import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioTrack;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import dev.onvoid.webrtc.RTCRtpTransceiver;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Стресс: ОБА пира одновременно шлют звук демо друг другу несколько секунд.
 * Ловит гонку двух производителей в нативном {@code AudioSendStream::SendAudioData}
 * (ADM-захват + push из CustomAudioSource) — она проявляется как abort JVM
 * («Check failed: !race_checker.RaceDetected()», audio_send_stream.cc). На
 * отдающем движке с headless-ADM падает за ~3с; с pushOnly-движком — проходит.
 */
class ScreenAudioBidirectionalStressTest {

    private static final long PUSH_SECONDS = 8;

    @Test
    void bothSidesPushConcurrentlyWithoutCrash() throws Exception {
        WebRtcEngine engine = WebRtcEngine.headless();      // главные сессии + приём звука демо
        WebRtcEngine sendEngine = WebRtcEngine.pushOnly();  // отдача звука демо
        PeerSession[] main = new PeerSession[2];
        RTCDataChannel[] ctrl = new RTCDataChannel[2];
        CountDownLatch ctrlReady = new CountDownLatch(2);

        main[0] = new PeerSession(engine, new PeerSession.Signals() {
            @Override public void onLocalDescription(RTCSessionDescription d) { main[1].onRemoteDescription(d); }
            @Override public void onLocalIceCandidate(RTCIceCandidate c) { main[1].onRemoteIceCandidate(c); }
        });
        main[1] = new PeerSession(engine, new PeerSession.Signals() {
            @Override public void onLocalDescription(RTCSessionDescription d) { main[0].onRemoteDescription(d); }
            @Override public void onLocalIceCandidate(RTCIceCandidate c) { main[0].onRemoteIceCandidate(c); }
            @Override public void onDataChannel(RTCDataChannel channel) { ctrl[1] = channel; ctrlReady.countDown(); }
        });
        ctrl[0] = main[0].createDataChannel("control");
        ctrlReady.countDown();
        main[0].createOffer();
        assertTrue(ctrlReady.await(20, TimeUnit.SECONDS), "control-каналы не готовы");

        CountDownLatch heardA = new CountDownLatch(1);
        CountDownLatch heardB = new CountDownLatch(1);
        SystemAudioTrack satA = new SystemAudioTrack(sendEngine);
        SystemAudioTrack satB = new SystemAudioTrack(sendEngine);

        ScreenAudioConnection saA = new ScreenAudioConnection(
                sendEngine, engine, ctrl[0], satA.track(), listen(heardA), UnaryOperator.identity());
        ScreenAudioConnection saB = new ScreenAudioConnection(
                sendEngine, engine, ctrl[1], satB.track(), listen(heardB), UnaryOperator.identity());

        satA.start(new FakeSystemAudioProvider(440));
        satB.start(new FakeSystemAudioProvider(660));

        assertTrue(heardA.await(25, TimeUnit.SECONDS), "A не услышал B");
        assertTrue(heardB.await(25, TimeUnit.SECONDS), "B не услышал A");

        // Держим оба push-потока ещё несколько секунд: гонка вероятностная.
        Thread.sleep(TimeUnit.SECONDS.toMillis(PUSH_SECONDS));

        satA.stop();
        satB.stop();
        saA.close();
        saB.close();
        main[0].close();
        main[1].close();
        engine.dispose();
        sendEngine.dispose();
    }

    private static Consumer<RTCRtpTransceiver> listen(CountDownLatch heard) {
        return transceiver -> {
            MediaStreamTrack track = transceiver.getReceiver().getTrack();
            if (track instanceof AudioTrack audio) {
                audio.addSink((data, bps, sr, ch, fr) -> {
                    for (byte x : data) {
                        if (x != 0) { heard.countDown(); return; }
                    }
                });
            }
        };
    }
}
