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

import java.util.ArrayList;
import java.util.List;

/**
 * Транспорт-независимая логика сигналинга: получив сообщение от соединения,
 * возвращает список адресных отправок. Не знает про WebSocket — это позволяет
 * покрыть всю маршрутизацию юнит-тестами.
 */
public final class SignalingService {

    private final RoomRegistry registry = new RoomRegistry();

    public List<Outbound> onMessage(String connId, SignalMessage msg) {
        List<Outbound> out = new ArrayList<>();
        if (msg instanceof Join j) {
            registry.join(connId, j.name(), j.room());
            List<Peer> peers = registry.peersInRoom(j.room(), connId);
            out.add(new Outbound(connId, new RoomState(connId, peers)));
            for (Peer p : peers) {
                out.add(new Outbound(p.id(), new PeerJoined(connId, j.name())));
            }
        } else if (msg instanceof Offer o) {
            out.add(new Outbound(o.peer(), new Offer(connId, o.sdp())));
        } else if (msg instanceof Answer a) {
            out.add(new Outbound(a.peer(), new Answer(connId, a.sdp())));
        } else if (msg instanceof Ice ice) {
            out.add(new Outbound(ice.peer(), new Ice(connId, ice.candidate(), ice.sdpMid(), ice.sdpMLineIndex())));
        } else if (msg instanceof TrackMeta tm) {
            String room = registry.roomOf(connId);
            if (room != null) {
                for (Peer p : registry.peersInRoom(room, connId)) {
                    out.add(new Outbound(p.id(), new TrackMeta(connId, tm.kind(), tm.state())));
                }
            }
        }
        return out;
    }

    public List<Outbound> onDisconnect(String connId) {
        String room = registry.roomOf(connId);
        List<Outbound> out = new ArrayList<>();
        if (room != null) {
            for (Peer p : registry.peersInRoom(room, connId)) {
                out.add(new Outbound(p.id(), new PeerLeft(connId)));
            }
        }
        registry.leave(connId);
        return out;
    }
}
