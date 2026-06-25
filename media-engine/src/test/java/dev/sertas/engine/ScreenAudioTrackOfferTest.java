package dev.sertas.engine;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.media.audio.AudioOptions;
import dev.onvoid.webrtc.media.audio.AudioTrack;
import dev.onvoid.webrtc.media.audio.AudioTrackSource;
import dev.onvoid.webrtc.media.audio.CustomAudioSource;
import dev.sertas.media.OpusSdpMunger;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Реальный offer с двумя аудио-треками (микрофон + screen-audio). Проверяет, что
 * метка трека screen-audio попадает в a=msid и что m-line-aware munging навешивает
 * музыкальный профиль ТОЛЬКО на секцию демо-звука, не трогая микрофон.
 */
class ScreenAudioTrackOfferTest {

    @Test
    void onlyScreenAudioSectionGetsMusicProfileInRealOffer() throws Exception {
        WebRtcEngine engine = WebRtcEngine.headless();
        PeerConnectionFactory factory = engine.factory();

        AudioTrackSource micSource = factory.createAudioSource(new AudioOptions());
        AudioTrack mic = factory.createAudioTrack("mic", micSource);
        CustomAudioSource screenSource = new CustomAudioSource();
        AudioTrack screen = factory.createAudioTrack(SystemAudioTrack.LABEL, screenSource);

        RTCPeerConnection pc = engine.createPeerConnection(new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
            }
        });

        try {
            pc.addTrack(mic, List.of("sertas"));
            pc.addTrack(screen, List.of("sertas"));

            CompletableFuture<String> sdp = new CompletableFuture<>();
            pc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
                @Override
                public void onSuccess(RTCSessionDescription description) {
                    sdp.complete(description.sdp);
                }

                @Override
                public void onFailure(String error) {
                    sdp.completeExceptionally(new IllegalStateException(error));
                }
            });

            String offer = sdp.get(10, TimeUnit.SECONDS);
            assertTrue(offer.contains(SystemAudioTrack.LABEL),
                    "метка трека не попала в SDP (a=msid) — подбор секции по метке не сработает:\n" + offer);

            String munged = OpusSdpMunger.applyMusicProfileToTrack(offer, SystemAudioTrack.LABEL);
            assertNotEquals(offer, munged, "munger ничего не изменил");
            // Однозначный маркер музыкального профиля (в отличие от "stereo=1",
            // который как подстрока есть и в "sprop-stereo=1").
            assertEquals(1, count(munged, "maxaveragebitrate=192000"),
                    "должна мунжиться ровно одна аудио-секция:\n" + munged);
            assertTrue(sectionContaining(munged, SystemAudioTrack.LABEL).contains("stereo=1"),
                    "секция screen-audio должна быть stereo");
            assertFalse(sectionContaining(munged, "mic").contains("stereo=1"),
                    "микрофон не должен мунжиться");
        } finally {
            pc.close();
            engine.dispose();
        }
    }

    private static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }

    private static String sectionContaining(String sdp, String marker) {
        int mark = sdp.indexOf("msid:sertas " + marker);
        int start = sdp.lastIndexOf("m=", mark);
        int next = sdp.indexOf("\r\nm=", mark);
        if (next < 0) {
            next = sdp.indexOf("\nm=", mark);
        }
        int end = next < 0 ? sdp.length() : next;
        return sdp.substring(start, end);
    }
}
