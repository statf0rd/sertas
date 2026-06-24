package dev.sertas.signaling;

import dev.sertas.protocol.SignalMessage;
import dev.sertas.protocol.SignalMessage.Join;
import dev.sertas.protocol.SignalMessage.Offer;
import dev.sertas.protocol.SignalMessage.PeerJoined;
import dev.sertas.protocol.SignalMessage.RoomState;
import dev.sertas.signaling.client.SignalingClient;
import dev.sertas.signaling.client.SignalingListener;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Поднимает реальный сервер на эфемерном порту и гоняет двух клиентов. */
class SignalingEndToEndTest {

    static SignalingServer server;
    static int port;

    @BeforeAll
    static void up() {
        server = new SignalingServer();
        server.start(0);
        port = server.port();
    }

    @AfterAll
    static void down() {
        server.stop();
    }

    static final class Collector implements SignalingListener {
        final BlockingQueue<SignalMessage> q = new LinkedBlockingQueue<>();
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void onMessage(SignalMessage m) {
            q.add(m);
        }

        @Override
        public void onClose(int code, String reason) {
            closed.countDown();
        }

        SignalMessage take() throws InterruptedException {
            SignalMessage m = q.poll(5, TimeUnit.SECONDS);
            assertNotNull(m, "timed out waiting for signaling message");
            return m;
        }
    }

    @Test
    void twoPeersJoinAndRelayOffer() throws Exception {
        String url = "ws://localhost:" + port + "/signal";
        var ca = new Collector();
        var cb = new Collector();
        var alice = new SignalingClient(ca);
        var bob = new SignalingClient(cb);

        alice.connect(url).get(5, TimeUnit.SECONDS);
        alice.send(new Join("ROOM", "Alice"));
        assertInstanceOf(RoomState.class, ca.take());

        bob.connect(url).get(5, TimeUnit.SECONDS);
        bob.send(new Join("ROOM", "Bob"));

        // Alice узнаёт о Bob
        SignalMessage joined = ca.take();
        assertInstanceOf(PeerJoined.class, joined);
        assertEquals("Bob", ((PeerJoined) joined).name());

        // Bob берёт id Alice из своего room-state и шлёт ей offer
        RoomState bobState = (RoomState) cb.take();
        String aliceId = bobState.peers().get(0).id();
        bob.send(new Offer(aliceId, "SDP-DATA"));

        SignalMessage offer = ca.take();
        assertInstanceOf(Offer.class, offer);
        assertEquals("SDP-DATA", ((Offer) offer).sdp());

        alice.close();
        bob.close();
    }

    @Test
    void rejectsConnectionWithoutValidToken() throws Exception {
        SignalingServer secured = new SignalingServer().requireToken("s3cret");
        secured.start(0);
        String secureUrl = "ws://localhost:" + secured.port() + "/signal";
        try {
            // без токена — соединение закрывается, RoomState не приходит
            var noToken = new Collector();
            var c1 = new SignalingClient(noToken);
            c1.connect(secureUrl).get(5, TimeUnit.SECONDS);
            c1.send(new Join("ROOM", "X"));
            assertTrue(noToken.closed.await(5, TimeUnit.SECONDS), "соединение без токена должно закрыться");
            assertNull(noToken.q.poll(1, TimeUnit.SECONDS), "без токена не должно быть RoomState");

            // с правильным токеном — работает
            var withToken = new Collector();
            var c2 = new SignalingClient(withToken);
            c2.connect(secureUrl + "?token=s3cret").get(5, TimeUnit.SECONDS);
            c2.send(new Join("ROOM", "Y"));
            assertInstanceOf(RoomState.class, withToken.take());
            c2.close();
        } finally {
            secured.stop();
        }
    }
}
