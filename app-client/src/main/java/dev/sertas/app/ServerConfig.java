package dev.sertas.app;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Откуда брать адрес сигналинг-сервера по умолчанию. Реальный хост НЕ хранится в
 * коде (репозиторий публичный) — он берётся из локальной конфигурации:
 *
 * <ol>
 *   <li>системное свойство {@code -Dsertas.server=...}</li>
 *   <li>переменная окружения {@code SERTAS_SERVER}</li>
 *   <li>файл {@code ~/.sertas/server} (одна строка с URL)</li>
 *   <li>иначе — {@code ws://localhost:8080/signal}</li>
 * </ol>
 */
public final class ServerConfig {

    private ServerConfig() {}

    private static final String FALLBACK = "ws://localhost:8080/signal";

    public static String defaultServerUrl() {
        String prop = System.getProperty("sertas.server");
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        String env = System.getenv("SERTAS_SERVER");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        try {
            Path p = Path.of(System.getProperty("user.home"), ".sertas", "server");
            if (Files.isReadable(p)) {
                String line = Files.readString(p).strip();
                if (!line.isEmpty()) {
                    return line;
                }
            }
        } catch (Exception ignored) {
            // нет локального конфига — используем fallback
        }
        return FALLBACK;
    }
}
