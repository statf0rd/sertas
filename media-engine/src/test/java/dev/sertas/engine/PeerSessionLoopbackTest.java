package dev.sertas.engine;

import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSessionDescription;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Два {@link PeerSession} в одном процессе устанавливают РЕАЛЬНОЕ WebRTC-соединение
 * (offer/answer + обмен ICE-кандидатами по loopback) и передают сообщение через
 * data-channel. Проверяет полный стек установления связи: ICE → DTLS → SCTP.
 */
class PeerSessionLoopbackTest {

    @Test
    void twoPeersConnectAndExchangeDataChannelMessage() throws Exception {
        WebRtcEngine engine = WebRtcEngine.headless();
        PeerSession[] holder = new PeerSession[2];

        CompletableFuture<String> received = new CompletableFuture<>();
        CountDownLatch channelOpen = new CountDownLatch(1);

        // Сторона A (инициатор)
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

        // Сторона B (отвечающий) — принимает data-channel
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
            public void onDataChannel(RTCDataChannel channel) {
                channel.registerObserver(new RTCDataChannelObserver() {
                    @Override
                    public void onBufferedAmountChange(long previousAmount) {}

                    @Override
                    public void onStateChange() {}

                    @Override
                    public void onMessage(RTCDataChannelBuffer buffer) {
                        byte[] bytes = new byte[buffer.data.remaining()];
                        buffer.data.get(bytes);
                        received.complete(new String(bytes, StandardCharsets.UTF_8));
                    }
                });
            }
        });

        holder[0] = a;
        holder[1] = b;

        RTCDataChannel dc = a.createDataChannel("chat");
        dc.registerObserver(new RTCDataChannelObserver() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {}

            @Override
            public void onStateChange() {
                if (dc.getState() == RTCDataChannelState.OPEN) {
                    channelOpen.countDown();
                }
            }

            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {}
        });

        a.createOffer();

        assertTrue(channelOpen.await(20, TimeUnit.SECONDS), "data-channel не открылся — соединение не установилось");
        dc.send(new RTCDataChannelBuffer(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8)), false));
        assertEquals("hello", received.get(20, TimeUnit.SECONDS));

        a.close();
        b.close();
        engine.dispose();
    }

    @Test
    void connectionReachesConnectedState() throws Exception {
        WebRtcEngine engine = WebRtcEngine.headless();
        PeerSession[] holder = new PeerSession[2];
        CountDownLatch connected = new CountDownLatch(1);

        PeerSession a = new PeerSession(engine, new PeerSession.Signals() {
            @Override
            public void onLocalDescription(RTCSessionDescription d) {
                holder[1].onRemoteDescription(d);
            }

            @Override
            public void onLocalIceCandidate(RTCIceCandidate c) {
                holder[1].onRemoteIceCandidate(c);
            }

            @Override
            public void onConnectionState(RTCPeerConnectionState state) {
                if (state == RTCPeerConnectionState.CONNECTED) {
                    connected.countDown();
                }
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
        });

        holder[0] = a;
        holder[1] = b;

        a.createDataChannel("chat");
        a.createOffer();

        assertTrue(connected.await(20, TimeUnit.SECONDS), "состояние CONNECTED не достигнуто");

        a.close();
        b.close();
        engine.dispose();
    }
}
