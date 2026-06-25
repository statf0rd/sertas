package dev.sertas.engine;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.media.video.VideoTrack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Воспроизводит путь нового {@code CallController.join()}: создать видео-трек из
 * НЕнастроенного (не выбран экран, не запущен) {@link ScreenCaptureSource} и
 * сгенерировать offer. Проверяет, что это не бросает и не виснет — иначе
 * приложение не дойдёт до подключения к серверу.
 */
class ScreenTrackOfferTest {

    @Test
    void unconfiguredScreenSourceTrackCreatesOffer() throws Exception {
        WebRtcEngine engine = WebRtcEngine.headless();
        PeerConnectionFactory factory = engine.factory();

        ScreenCaptureSource screen = new ScreenCaptureSource();
        VideoTrack screenTrack = engine.createVideoTrack("screen", screen.source());

        RTCPeerConnection pc = engine.createPeerConnection(new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
            }
        });

        try {
            pc.addTrack(screenTrack, List.of("stream0"));

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
            assertTrue(offer.contains("m=video"), "нет видео m-line:\n" + offer);
        } finally {
            pc.close();
            screen.dispose();
            engine.dispose();
        }
    }
}
