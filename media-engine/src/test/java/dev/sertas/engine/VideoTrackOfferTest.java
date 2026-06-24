package dev.sertas.engine;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.media.FourCC;
import dev.onvoid.webrtc.media.video.CustomVideoSource;
import dev.onvoid.webrtc.media.video.NativeI420Buffer;
import dev.onvoid.webrtc.media.video.VideoBufferConverter;
import dev.onvoid.webrtc.media.video.VideoFrame;
import dev.onvoid.webrtc.media.video.VideoTrack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Прогоняет путь захвата видео БЕЗ устройства: RGBA-кадр &rarr; I420 &rarr;
 * {@link CustomVideoSource#pushFrame} &rarr; видео-трек &rarr; реальный offer с
 * {@code m=video}. Это в точности путь, которым будут вливаться кадры экрана
 * (демонстрация) — деривативный риск Фазы 2 снят здесь.
 */
class VideoTrackOfferTest {

    @Test
    void customVideoSourceProducesVideoOffer() throws Exception {
        WebRtcEngine engine = WebRtcEngine.headless();
        PeerConnectionFactory factory = engine.factory();

        CustomVideoSource source = new CustomVideoSource();
        VideoTrack track = factory.createVideoTrack("screen", source);

        RTCPeerConnection pc = engine.createPeerConnection(new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
            }
        });

        try {
            // Один чёрный кадр 64x48: RGBA -> I420 -> pushFrame (как с кадром экрана).
            int width = 64;
            int height = 48;
            NativeI420Buffer i420 = NativeI420Buffer.allocate(width, height);
            VideoBufferConverter.convertToI420(new byte[width * height * 4], i420, FourCC.RGBA);
            VideoFrame frame = new VideoFrame(i420, 0L);
            source.pushFrame(frame);
            frame.release();

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
            assertTrue(offer.contains("m=video"), "нет видео m-line:\n" + offer);
            assertTrue(offer.contains("VP8") || offer.contains("VP9") || offer.contains("H264"),
                    "нет видеокодека:\n" + offer);
        } finally {
            pc.close();
            engine.dispose();
        }
    }
}
