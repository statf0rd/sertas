package dev.sertas.app;

/**
 * Точка входа для запуска с classpath (портативный бандл). НЕ наследует
 * {@code Application} — иначе JVM-лаунчер падает с «JavaFX runtime components
 * are missing», когда JavaFX лежит на classpath, а не на module-path.
 */
public final class Launcher {

    private Launcher() {}

    public static void main(String[] args) {
        SertasApp.main(args);
    }
}
