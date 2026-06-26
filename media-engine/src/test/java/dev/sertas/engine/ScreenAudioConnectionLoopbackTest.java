package dev.sertas.engine;

import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioTrack;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Главное соединение (с control data-channel) + ВТОРОЕ соединение для звука демо,
 * чьи offer/answer/ICE туннелируются через этот data-channel. Проверяет, что тон,
 * запушенный в screen-audio трек второго соединения, доходит до зрителя — то есть
 * весь механизм отдельного соединения для звука демо работает.
 */
class ScreenAudioConnectionLoopbackTest {

    @Test
    void tonesFlowOverTunneledSecondConnection() throws Exception {
        WebRtcEngine engine = WebRtcEngine.headless();
        PeerSession[] main = new PeerSession[2];
        RTCDataChannel[] ctrl = new RTCDataChannel[2];
        CountDownLatch ctrlReady = new CountDownLatch(2);

        PeerSession a = new PeerSession(engine, new PeerSession.Signals() {
            @Override public void onLocalDescription(RTCSessionDescription d) { main[1].onRemoteDescription(d); }
            @Override public void onLocalIceCandidate(RTCIceCandidate c) { main[1].onRemoteIceCandidate(c); }
        });
        PeerSession b = new PeerSession(engine, new PeerSession.Signals() {
            @Override public void onLocalDescription(RTCSessionDescription d) { main[0].onRemoteDescription(d); }
            @Override public void onLocalIceCandidate(RTCIceCandidate c) { main[0].onRemoteIceCandidate(c); }
            @Override public void onDataChannel(RTCDataChannel channel) { ctrl[1] = channel; ctrlReady.countDown(); }
        });
        main[0] = a;
        main[1] = b;

        ctrl[0] = a.createDataChannel("control");
        ctrlReady.countDown();
        a.createOffer();

        assertTrue(ctrlReady.await(20, TimeUnit.SECONDS), "control-каналы не готовы");

        CountDownLatch heard = new CountDownLatch(1);
        SystemAudioTrack sat = new SystemAudioTrack(engine);

        // A (инициатор) шлёт screen-audio; B (зритель) принимает и ловит ненулевой звук.
        ScreenAudioConnection saA = new ScreenAudioConnection(
                engine, ctrl[0], true, sat.track(), t -> {}, UnaryOperator.identity());
        ScreenAudioConnection saB = new ScreenAudioConnection(
                engine, ctrl[1], false, null, transceiver -> {
            MediaStreamTrack track = transceiver.getReceiver().getTrack();
            if (track instanceof AudioTrack audio) {
                audio.addSink((data, bps, sr, ch, fr) -> {
                    for (byte x : data) {
                        if (x != 0) { heard.countDown(); return; }
                    }
                });
            }
        }, UnaryOperator.identity());

        sat.start(new FakeSystemAudioProvider(440));

        assertTrue(heard.await(25, TimeUnit.SECONDS),
                "тон не дошёл по туннелированному второму соединению");

        sat.stop();
        saA.close();
        saB.close();
        a.close();
        b.close();
        engine.dispose();
    }
}
