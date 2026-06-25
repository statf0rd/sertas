package dev.sertas.protocol;

import java.util.List;

/**
 * ICE-сервер (STUN/TURN) для установления соединения. Сервер отдаёт их клиенту в
 * {@link SignalMessage.RoomState} — так креды TURN остаются только на сервере и
 * не попадают в бинарник/репозиторий.
 */
public record IceServer(List<String> urls, String username, String credential) {}
