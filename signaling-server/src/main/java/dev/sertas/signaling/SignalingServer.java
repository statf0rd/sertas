package dev.sertas.signaling;

import dev.sertas.protocol.SignalCodec;
import dev.sertas.protocol.SignalMessage;
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

    /**
     * Если задан — подключения без совпадающего {@code ?token=} отклоняются.
     * По умолчанию берётся из переменной окружения {@code SERTAS_TOKEN};
     * {@code null} = без авторизации (для локальной разработки).
     */
    private String requiredToken = System.getenv("SERTAS_TOKEN");

    /** Задать обязательный токен программно (например, в тестах). */
    public SignalingServer requireToken(String token) {
        this.requiredToken = token;
        return this;
    }

    public void start(int port) {
        app = Javalin.create().ws("/signal", ws -> {
            ws.onConnect(ctx -> {
                if (requiredToken != null && !requiredToken.equals(ctx.queryParam("token"))) {
                    System.out.println("REJECT(token) " + ctx.sessionId());
                    ctx.closeSession(1008, "unauthorized"); // 1008 = policy violation
                    return;
                }
                ctx.enableAutomaticPings();
                conns.put(ctx.sessionId(), ctx);
                System.out.println("CONNECT " + ctx.sessionId());
            });
            ws.onMessage(ctx -> {
                SignalMessage msg = codec.decode(ctx.message());
                if (msg instanceof SignalMessage.Join j) {
                    System.out.println("JOIN " + ctx.sessionId()
                            + " room=[" + j.room() + "] len=" + j.room().length()
                            + " name=[" + j.name() + "]");
                }
                dispatch(service.onMessage(ctx.sessionId(), msg));
            });
            ws.onClose(ctx -> {
                System.out.println("CLOSE " + ctx.sessionId());
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
