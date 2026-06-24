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
import dev.sertas.media.OpusSdpMunger;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Связывает media-engine с медиа-утилитами: добавляет реальный аудио-трек,
 * генерирует настоящий offer от libwebrtc и проверяет, что {@link OpusSdpMunger}
 * применяет «музыкальный» профиль к РЕАЛЬНОМУ SDP (а не к синтетическому образцу).
 */
class AudioTrackOfferTest {

    @Test
    void realOfferHasOpusAndMungerAppliesMusicProfile() throws Exception {
        WebRtcEngine engine = WebRtcEngine.headless();
        PeerConnectionFactory factory = engine.factory();

        AudioTrackSource source = factory.createAudioSource(new AudioOptions());
        AudioTrack track = factory.createAudioTrack("mic", source);

        RTCPeerConnection pc = engine.createPeerConnection(new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
            }
        });

        try {
            pc.addTrack(track, List.of("stream0"));

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
            assertTrue(offer.contains("m=audio"), "нет аудио m-line:\n" + offer);
            assertTrue(offer.toLowerCase().contains("opus"), "нет Opus в offer:\n" + offer);

            String munged = OpusSdpMunger.applyMusicProfile(offer);
            assertNotEquals(offer, munged, "munger ничего не изменил в реальном SDP");
            assertTrue(munged.contains("stereo=1"), "музыкальный профиль не применён:\n" + munged);
            assertTrue(munged.contains("usedtx=0"), "usedtx=0 не применён");
        } finally {
            // Трек держит sender — отдельный track.dispose() здесь бросает
            // "Native object ... reference is still around". Достаточно закрыть
            // peer-connection и освободить фабрику.
            pc.close();
            engine.dispose();
        }
    }
}
