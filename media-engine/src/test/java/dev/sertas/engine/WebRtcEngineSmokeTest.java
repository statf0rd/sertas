package dev.sertas.engine;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCSessionDescription;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Прогоняет нативный стек webrtc-java на хосте: создаёт фабрику, peer-connection,
 * data-channel и генерирует SDP-offer. Доказывает, что нативная библиотека
 * (macos-aarch64 и т.д.) загружается и работает. Без реальных аудио-устройств.
 */
class WebRtcEngineSmokeTest {

    @Test
    void createsOfferThroughNativeStack() throws Exception {
        WebRtcEngine engine = WebRtcEngine.headless();
        RTCPeerConnection pc = engine.createPeerConnection(new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                // в этом тесте кандидаты не нужны
            }
        });
        try {
            pc.createDataChannel("ping", new RTCDataChannelInit());

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
            assertTrue(offer.contains("v=0"), offer);
            assertTrue(offer.contains("m=application"), "ожидался m-line data-channel:\n" + offer);
        } finally {
            pc.close();
            engine.dispose();
        }
    }
}
