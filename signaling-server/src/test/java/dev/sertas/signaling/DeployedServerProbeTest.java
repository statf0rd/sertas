package dev.sertas.signaling;

import dev.sertas.protocol.SignalMessage;
import dev.sertas.protocol.SignalMessage.Join;
import dev.sertas.protocol.SignalMessage.PeerJoined;
import dev.sertas.protocol.SignalMessage.RoomState;
import dev.sertas.signaling.client.SignalingClient;
import dev.sertas.signaling.client.SignalingListener;

import org.junit.jupiter.api.Test;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Диагностика: подключается к РЕАЛЬНОМУ серверу из env {@code SERTAS_TEST_URL}
 * (полный ws-URL с токеном) и проверяет, что два клиента в одной комнате видят
 * друг друга. Без env — тест пропускается. Запуск:
 *   SERTAS_TEST_URL='ws://HOST:8080/signal?token=...' ./gradlew :signaling-server:test --tests '*DeployedServerProbeTest*'
 */
class DeployedServerProbeTest {

    static final class Q implements SignalingListener {
        final BlockingQueue<SignalMessage> q = new LinkedBlockingQueue<>();
        public void onMessage(SignalMessage m) { q.add(m); }
        SignalMessage poll() throws InterruptedException { return q.poll(8, TimeUnit.SECONDS); }
    }

    @Test
    void twoClientsSeeEachOtherInSameRoom() throws Exception {
        String url = System.getenv("SERTAS_TEST_URL");
        assumeTrue(url != null && !url.isBlank(), "set SERTAS_TEST_URL to run");

        Q qa = new Q();
        Q qb = new Q();
        SignalingClient a = new SignalingClient(qa);
        SignalingClient b = new SignalingClient(qb);

        a.connect(url).get(8, TimeUnit.SECONDS);
        a.send(new Join("test", "ProbeA"));
        SignalMessage aState = qa.poll();
        System.out.println("[A] first message: " + aState);
        assertInstanceOf(RoomState.class, aState, "A не получил room-state (токен/комната?)");

        b.connect(url).get(8, TimeUnit.SECONDS);
        b.send(new Join("test", "ProbeB"));
        SignalMessage bState = qb.poll();
        System.out.println("[B] first message: " + bState);
        assertInstanceOf(RoomState.class, bState);
        System.out.println("[B] sees peers already in test: " + ((RoomState) bState).peers());

        SignalMessage aSeesB = qa.poll();
        System.out.println("[A] next message after B joined: " + aSeesB);
        assertInstanceOf(PeerJoined.class, aSeesB, "A не увидел подключение B — сервер не релеит");
        assertEquals("ProbeB", ((PeerJoined) aSeesB).name());

        a.close();
        b.close();
    }
}
