package dev.sertas.signaling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoomRegistryTest {

    @Test
    void joinAddsParticipantAndListsPeersExcludingSelf() {
        var r = new RoomRegistry();
        r.join("a", "Alice", "ROOM");
        r.join("b", "Bob", "ROOM");
        assertEquals("ROOM", r.roomOf("a"));
        assertEquals(1, r.peersInRoom("ROOM", "a").size());
        assertEquals("b", r.peersInRoom("ROOM", "a").get(0).id());
    }

    @Test
    void peersAreScopedToTheSameRoom() {
        var r = new RoomRegistry();
        r.join("a", "Alice", "ROOM1");
        r.join("b", "Bob", "ROOM2");
        assertTrue(r.peersInRoom("ROOM1", "a").isEmpty());
    }

    @Test
    void leaveReturnsRoomAndRemoves() {
        var r = new RoomRegistry();
        r.join("a", "Alice", "ROOM");
        assertEquals("ROOM", r.leave("a"));
        assertNull(r.roomOf("a"));
    }

    @Test
    void leaveUnknownReturnsNull() {
        assertNull(new RoomRegistry().leave("ghost"));
    }
}
