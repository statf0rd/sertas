package dev.sertas.signaling;

import dev.sertas.protocol.Peer;
import dev.sertas.protocol.SignalMessage;
import dev.sertas.protocol.SignalMessage.Answer;
import dev.sertas.protocol.SignalMessage.Ice;
import dev.sertas.protocol.SignalMessage.Join;
import dev.sertas.protocol.SignalMessage.Offer;
import dev.sertas.protocol.SignalMessage.PeerJoined;
import dev.sertas.protocol.SignalMessage.PeerLeft;
import dev.sertas.protocol.SignalMessage.RoomState;
import dev.sertas.protocol.SignalMessage.TrackMeta;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SignalingServiceTest {

    @Test
    void joinNotifiesSelfStateAndExistingPeers() {
        var svc = new SignalingService();
        svc.onMessage("a", new Join("ROOM", "Alice"));   // первый — пиров нет
        List<Outbound> out = svc.onMessage("b", new Join("ROOM", "Bob"));
        assertTrue(out.contains(new Outbound("b",
                new RoomState("b", List.of(new Peer("a", "Alice"))))), out.toString());
        assertTrue(out.contains(new Outbound("a", new PeerJoined("b", "Bob"))), out.toString());
    }

    @Test
    void firstJoinGetsEmptyRoomStateAndNoBroadcast() {
        var svc = new SignalingService();
        List<Outbound> out = svc.onMessage("a", new Join("ROOM", "Alice"));
        assertEquals(List.of(new Outbound("a", new RoomState("a", List.of()))), out);
    }

    @Test
    void offerIsRelayedToTargetWithSenderAsPeer() {
        var svc = new SignalingService();
        svc.onMessage("a", new Join("ROOM", "Alice"));
        svc.onMessage("b", new Join("ROOM", "Bob"));
        List<Outbound> out = svc.onMessage("a", new Offer("b", "SDP"));
        assertEquals(List.of(new Outbound("b", new Offer("a", "SDP"))), out);
    }

    @Test
    void answerAndIceAreRelayed() {
        var svc = new SignalingService();
        svc.onMessage("a", new Join("ROOM", "Alice"));
        svc.onMessage("b", new Join("ROOM", "Bob"));
        assertEquals(List.of(new Outbound("b", new Answer("a", "SDP"))),
                svc.onMessage("a", new Answer("b", "SDP")));
        assertEquals(List.of(new Outbound("b", new Ice("a", "cand", "0", 0))),
                svc.onMessage("a", new Ice("b", "cand", "0", 0)));
    }

    @Test
    void trackMetaBroadcastsToRoomWithSenderAsPeer() {
        var svc = new SignalingService();
        svc.onMessage("a", new Join("ROOM", "Alice"));
        svc.onMessage("b", new Join("ROOM", "Bob"));
        svc.onMessage("c", new Join("ROOM", "Carol"));
        List<Outbound> out = svc.onMessage("a", new TrackMeta(null, "screen", "on"));
        assertEquals(2, out.size());
        assertTrue(out.contains(new Outbound("b", new TrackMeta("a", "screen", "on"))), out.toString());
        assertTrue(out.contains(new Outbound("c", new TrackMeta("a", "screen", "on"))), out.toString());
    }

    @Test
    void disconnectNotifiesRemainingPeers() {
        var svc = new SignalingService();
        svc.onMessage("a", new Join("ROOM", "Alice"));
        svc.onMessage("b", new Join("ROOM", "Bob"));
        assertEquals(List.of(new Outbound("b", new PeerLeft("a"))), svc.onDisconnect("a"));
    }
}
