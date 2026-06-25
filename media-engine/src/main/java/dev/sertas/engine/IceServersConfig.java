package dev.sertas.engine;

import dev.onvoid.webrtc.RTCIceServer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ICE-серверы для установления соединения. STUN — всегда; TURN (ретрансляция
 * медиа, когда прямой P2P между сетями невозможен из-за NAT) — опционально, из
 * конфигурации, чтобы креды не лежали в репозитории.
 *
 * <p>TURN берётся из (по приоритету): {@code -Dsertas.turn}, env
 * {@code SERTAS_TURN}, файл {@code ~/.sertas/turn}. Формат значения:
 * {@code turn:HOST:3478,USER,PASS} (разделитель — запятая или пробел).
 */
public final class IceServersConfig {

    private IceServersConfig() {}

    public static List<RTCIceServer> resolve() {
        List<RTCIceServer> servers = new ArrayList<>();

        RTCIceServer stun = new RTCIceServer();
        stun.urls = List.of("stun:stun.l.google.com:19302");
        servers.add(stun);

        String spec = turnSpec();
        if (spec != null) {
            String[] p = spec.trim().split("[,\\s]+");
            if (p.length >= 3) {
                String url = p[0];
                String tcpUrl = url.contains("?") ? url : url + "?transport=tcp";
                RTCIceServer turn = new RTCIceServer();
                turn.urls = List.of(url, tcpUrl); // UDP + TCP на случай сетей без UDP
                turn.username = p[1];
                turn.password = p[2];
                servers.add(turn);
            }
        }
        return servers;
    }

    private static String turnSpec() {
        String prop = System.getProperty("sertas.turn");
        if (prop != null && !prop.isBlank()) {
            return prop;
        }
        String env = System.getenv("SERTAS_TURN");
        if (env != null && !env.isBlank()) {
            return env;
        }
        try {
            Path f = Path.of(System.getProperty("user.home"), ".sertas", "turn");
            if (Files.isReadable(f)) {
                String s = Files.readString(f).strip();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        } catch (Exception ignored) {
            // нет конфига TURN — остаёмся на STUN
        }
        return null;
    }
}
