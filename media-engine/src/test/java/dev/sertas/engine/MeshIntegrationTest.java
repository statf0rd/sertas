package dev.sertas.engine;

import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.sertas.signaling.SignalingServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Полный вертикальный срез: реальный сигналинг-сервер + два {@link MeshCoordinator}
 * (каждый с собственным WebRTC-движком) входят в одну комнату и устанавливают
 * настоящее P2P-соединение через сервер. Прогоняет signaling-server +
 * signaling-client + media-engine вместе.
 */
class MeshIntegrationTest {

    static SignalingServer server;
    static String url;

    @BeforeAll
    static void up() {
        server = new SignalingServer();
        server.start(0);
        url = "ws://localhost:" + server.port() + "/signal";
    }

    @AfterAll
    static void down() {
        server.stop();
    }

    @Test
    void twoCoordinatorsConnectThroughRealServer() throws Exception {
        WebRtcEngine engineA = WebRtcEngine.headless();
        WebRtcEngine engineB = WebRtcEngine.headless();

        CountDownLatch aConnected = new CountDownLatch(1);
        CountDownLatch bConnected = new CountDownLatch(1);

        MeshCoordinator alice = new MeshCoordinator(engineA, new MeshListener() {
            @Override
            public void onPeerState(String peerId, RTCPeerConnectionState state) {
                if (state == RTCPeerConnectionState.CONNECTED) {
                    aConnected.countDown();
                }
            }

            @Override
            public void onError(Throwable error) {
                System.err.println("[Alice] " + error);
            }
        });
        MeshCoordinator bob = new MeshCoordinator(engineB, new MeshListener() {
            @Override
            public void onPeerState(String peerId, RTCPeerConnectionState state) {
                if (state == RTCPeerConnectionState.CONNECTED) {
                    bConnected.countDown();
                }
            }

            @Override
            public void onError(Throwable error) {
                System.err.println("[Bob] " + error);
            }
        });

        try {
            alice.start(url, "ROOM", "Alice");
            bob.start(url, "ROOM", "Bob");

            assertTrue(aConnected.await(30, TimeUnit.SECONDS), "Alice не соединилась с пиром");
            assertTrue(bConnected.await(30, TimeUnit.SECONDS), "Bob не соединился с пиром");
        } finally {
            alice.stop();
            bob.stop();
            engineA.dispose();
            engineB.dispose();
        }
    }
}
