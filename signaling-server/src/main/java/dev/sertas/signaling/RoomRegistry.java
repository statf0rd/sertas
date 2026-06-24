package dev.sertas.signaling;

import dev.sertas.protocol.Peer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Комнаты и участники в памяти. Потокобезопасно. */
public final class RoomRegistry {

    private final Map<String, Participant> byId = new ConcurrentHashMap<>();

    public void join(String id, String name, String room) {
        byId.put(id, new Participant(id, name, room));
    }

    /** @return имя покинутой комнаты или {@code null}, если участник неизвестен. */
    public String leave(String id) {
        Participant p = byId.remove(id);
        return p == null ? null : p.room();
    }

    public String roomOf(String id) {
        Participant p = byId.get(id);
        return p == null ? null : p.room();
    }

    /** Участники комнаты, кроме {@code excludeId}. */
    public List<Peer> peersInRoom(String room, String excludeId) {
        List<Peer> out = new ArrayList<>();
        for (Participant p : byId.values()) {
            if (p.room().equals(room) && !p.id().equals(excludeId)) {
                out.add(new Peer(p.id(), p.name()));
            }
        }
        return out;
    }

    public Participant get(String id) {
        return byId.get(id);
    }
}
