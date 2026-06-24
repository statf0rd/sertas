package dev.sertas.signaling;

import dev.sertas.protocol.SignalCodec;
import io.javalin.Javalin;
import io.javalin.websocket.WsContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket-обёртка над {@link SignalingService}. Сопоставляет id соединения
 * (Javalin sessionId) с {@link WsContext} и рассылает результаты сервиса.
 */
public final class SignalingServer {

    private final SignalingService service = new SignalingService();
    private final SignalCodec codec = new SignalCodec();
    private final Map<String, WsContext> conns = new ConcurrentHashMap<>();
    private Javalin app;

    public void start(int port) {
        app = Javalin.create().ws("/signal", ws -> {
            ws.onConnect(ctx -> {
                ctx.enableAutomaticPings();
                conns.put(ctx.sessionId(), ctx);
            });
            ws.onMessage(ctx ->
                    dispatch(service.onMessage(ctx.sessionId(), codec.decode(ctx.message()))));
            ws.onClose(ctx -> {
                dispatch(service.onDisconnect(ctx.sessionId()));
                conns.remove(ctx.sessionId());
            });
        }).start(port);
    }

    private void dispatch(List<Outbound> outs) {
        for (Outbound o : outs) {
            WsContext c = conns.get(o.recipientId());
            if (c == null) {
                continue;
            }
            try {
                c.send(codec.encode(o.message()));
            } catch (RuntimeException ignored) {
                // соединение закрылось между проверкой и отправкой
            }
        }
    }

    public int port() {
        return app.port();
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }

    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        new SignalingServer().start(port);
        System.out.println("signaling on ws://localhost:" + port + "/signal");
    }
}
