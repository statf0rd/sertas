package dev.sertas.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * Сообщение сигналинга. Сериализуется в JSON с полем {@code type}.
 *
 * <p>Поле {@code peer} в relay-сообщениях (Offer/Answer/Ice/TrackMeta) означает
 * адресата на пути client&rarr;server и источник на пути server&rarr;client —
 * сервер переписывает его на id отправителя.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SignalMessage.Join.class, name = "join"),
        @JsonSubTypes.Type(value = SignalMessage.RoomState.class, name = "room-state"),
        @JsonSubTypes.Type(value = SignalMessage.PeerJoined.class, name = "peer-joined"),
        @JsonSubTypes.Type(value = SignalMessage.PeerLeft.class, name = "peer-left"),
        @JsonSubTypes.Type(value = SignalMessage.Offer.class, name = "offer"),
        @JsonSubTypes.Type(value = SignalMessage.Answer.class, name = "answer"),
        @JsonSubTypes.Type(value = SignalMessage.Ice.class, name = "ice"),
        @JsonSubTypes.Type(value = SignalMessage.TrackMeta.class, name = "track-meta"),
})
public sealed interface SignalMessage {

    /** client&rarr;server: войти в комнату под именем. */
    record Join(String room, String name) implements SignalMessage {}

    /** server&rarr;client: твой id и текущие участники (кроме тебя). */
    record RoomState(String selfId, List<Peer> peers) implements SignalMessage {}

    /** server&rarr;client: присоединился новый участник. */
    record PeerJoined(String id, String name) implements SignalMessage {}

    /** server&rarr;client: участник вышел. */
    record PeerLeft(String id) implements SignalMessage {}

    /** relay: SDP-offer. */
    record Offer(String peer, String sdp) implements SignalMessage {}

    /** relay: SDP-answer. */
    record Answer(String peer, String sdp) implements SignalMessage {}

    /** relay: ICE-кандидат. */
    record Ice(String peer, String candidate, String sdpMid, int sdpMLineIndex) implements SignalMessage {}

    /** relay/broadcast: метаданные трека (mic|camera|screen|screen-audio + состояние). */
    record TrackMeta(String peer, String kind, String state) implements SignalMessage {}
}
