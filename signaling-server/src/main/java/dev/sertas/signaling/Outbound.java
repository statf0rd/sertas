package dev.sertas.signaling;

import dev.sertas.protocol.SignalMessage;

/** Что и кому отправить — результат работы {@link SignalingService}. */
public record Outbound(String recipientId, SignalMessage message) {}
