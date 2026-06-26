package dev.sertas.engine;

import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCRtpTransceiver;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.media.MediaStreamTrack;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Де-риск идентичности источников: отправитель шлёт два аудио-трека (mic + screen-audio),
 * приёмник через {@link RemoteAudioMixer} различает их по {@code track.getId()}. Лочит
 * предположение, что удалённый id сохраняет метку отправителя (а не подменяется на opaque).
 * Заодно проверяет, что тон демо проходит сквозь микшер.
 */
class RemoteAudioMixerLoopbackTest {

    @Test
    void receiverDistinguishesVoiceAndDemoAndMixesTone() throws Exception {
        WebRtcEngine engine = WebRtcEngine.headless();
        PeerSession[] holder = new PeerSession[2];
        Set<String> remoteIds = ConcurrentHashMap.newKeySet();
        RemoteAudioMixer mixer = new RemoteAudioMixer();
        CountDownLatch bothArrived = new CountDownLatch(1);

        PeerSession a = new PeerSession(engine, new PeerSession.Signals() {
            @Override public void onLocalDescription(RTCSessionDescription d) { holder[1].onRemoteDescription(d); }
            @Override public void onLocalIceCandidate(RTCIceCandidate c) { holder[1].onRemoteIceCandidate(c); }
        });
        PeerSession b = new PeerSession(engine, new PeerSession.Signals() {
            @Override public void onLocalDescription(RTCSessionDescription d) { holder[0].onRemoteDescription(d); }
            @Override public void onLocalIceCandidate(RTCIceCandidate c) { holder[0].onRemoteIceCandidate(c); }
            @Override public void onTrack(RTCRtpTransceiver transceiver) {
                MediaStreamTrack track = transceiver.getReceiver().getTrack();
                if (track instanceof AudioTrack audio) {
                    remoteIds.add(audio.getId());
                    mixer.attach("peerX", audio);
                    if (remoteIds.contains("mic") && remoteIds.contains(SystemAudioTrack.LABEL)) {
                        bothArrived.countDown();
                    }
                }
            }
        });
        holder[0] = a;
        holder[1] = b;

        AudioTrack mic = engine.factory().createAudioTrack("mic", engine.factory().createAudioSource(new AudioOptions()));
        a.addTrack(mic);
        SystemAudioTrack screenAudio = new SystemAudioTrack(engine);
        a.addTrack(screenAudio.track());
        a.createOffer();

        Thread.sleep(3_000);
        screenAudio.start(new FakeSystemAudioProvider(440));

        assertTrue(bothArrived.await(25, TimeUnit.SECONDS),
                "не пришли оба трека; remoteIds=" + remoteIds);

        // Идентичность: удалённый getId() сохранил метки отправителя.
        assertTrue(remoteIds.contains("mic"), remoteIds.toString());
        assertTrue(remoteIds.contains(SystemAudioTrack.LABEL), remoteIds.toString());
        // Микшер развёл источники по виду.
        assertTrue(mixer.hasSource("peerX", RemoteAudioMixer.Kind.VOICE));
        assertTrue(mixer.hasSource("peerX", RemoteAudioMixer.Kind.DEMO));

        // Тон демо доходит до микшера: за ~1с pull должен дать ненулевой звук.
        boolean heard = false;
        float[] out = new float[960];
        for (int i = 0; i < 100 && !heard; i++) {
            mixer.pull(out, 480);
            for (float v : out) {
                if (v != 0f) { heard = true; break; }
            }
            Thread.sleep(10);
        }
        assertTrue(heard, "тон демо не дошёл до микшера через sink");

        // detach снимает источники и sink'и без падения.
        mixer.detach("peerX");
        assertFalse(mixer.hasSource("peerX", RemoteAudioMixer.Kind.VOICE));
        assertFalse(mixer.hasSource("peerX", RemoteAudioMixer.Kind.DEMO));

        screenAudio.stop();
        a.close();
        b.close();
        engine.dispose();
    }
}
