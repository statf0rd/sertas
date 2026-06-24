package dev.sertas.signaling.client;

import dev.sertas.protocol.SignalCodec;
import dev.sertas.protocol.SignalMessage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletableFuture;

/**
 * Клиент сигналинга на {@link java.net.http.WebSocket} (без сторонних зависимостей).
 * Собирает фрагментированные текстовые кадры и декодирует их в {@link SignalMessage}.
 */
public final class SignalingClient implements WebSocket.Listener {

    private final SignalCodec codec = new SignalCodec();
    private final SignalingListener listener;
    private final StringBuilder buffer = new StringBuilder();
    private volatile WebSocket ws;

    public SignalingClient(SignalingListener listener) {
        this.listener = listener;
    }

    public CompletableFuture<Void> connect(String url) {
        return HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create(url), this)
                .thenAccept(w -> this.ws = w);
    }

    public void send(SignalMessage msg) {
        WebSocket w = ws;
        if (w != null) {
            w.sendText(codec.encode(msg), true);
        }
    }

    public void close() {
        WebSocket w = ws;
        if (w != null) {
            w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        this.ws = webSocket;
        webSocket.request(1);
        listener.onOpen();
    }

    @Override
    public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        buffer.append(data);
        if (last) {
            String json = buffer.toString();
            buffer.setLength(0);
            try {
                listener.onMessage(codec.decode(json));
            } catch (RuntimeException e) {
                listener.onError(e);
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        listener.onClose(statusCode, reason);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        listener.onError(error);
    }
}
