package dev.sertas.signaling.client;

import dev.sertas.protocol.SignalMessage;

/** Колбэки клиента сигналинга. {@link #onMessage} вызывается на потоке WebSocket. */
public interface SignalingListener {

    void onMessage(SignalMessage msg);

    default void onOpen() {}

    default void onClose(int code, String reason) {}

    default void onError(Throwable t) {}
}
