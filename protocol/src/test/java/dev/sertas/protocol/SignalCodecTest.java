package dev.sertas.protocol;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SignalCodecTest {

    private final SignalCodec codec = new SignalCodec();

    @Test
    void roundTripJoin() {
        var msg = new SignalMessage.Join("ROOM1", "Alice");
        String json = codec.encode(msg);
        assertTrue(json.contains("\"type\":\"join\""), json);
        assertEquals(msg, codec.decode(json));
    }

    @Test
    void roundTripRoomState() {
        var msg = new SignalMessage.RoomState("id-1", List.of(new Peer("id-2", "Bob")),
                List.of(new IceServer(List.of("stun:s:3478"), null, null)));
        assertEquals(msg, codec.decode(codec.encode(msg)));
    }

    @Test
    void roundTripOffer() {
        var msg = new SignalMessage.Offer("peer-9", "v=0\r\no=- 1 1 IN IP4 0.0.0.0");
        assertEquals(msg, codec.decode(codec.encode(msg)));
    }

    @Test
    void roundTripIce() {
        var msg = new SignalMessage.Ice("peer-9", "candidate:1 1 udp", "0", 0);
        assertEquals(msg, codec.decode(codec.encode(msg)));
    }

    @Test
    void roundTripTrackMeta() {
        var msg = new SignalMessage.TrackMeta("peer-9", "screen", "on");
        assertEquals(msg, codec.decode(codec.encode(msg)));
    }
}
