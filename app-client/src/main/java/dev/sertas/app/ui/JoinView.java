package dev.sertas.app.ui;

import dev.sertas.app.ServerConfig;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/** Экран входа в комнату. Адрес сервера берётся из {@link ServerConfig}. */
public final class JoinView {

    private final VBox root = new VBox(10);
    private final TextField server = new TextField(ServerConfig.defaultServerUrl());
    private final TextField room = new TextField();
    private final TextField name = new TextField();
    private final Button join = new Button("Войти в комнату");

    public JoinView() {
        root.setPadding(new Insets(20));
        room.setPromptText("Код комнаты");
        name.setPromptText("Ваше имя");
        root.getChildren().addAll(
                new Label("Сервер"), server,
                new Label("Комната"), room,
                new Label("Имя"), name,
                join);
    }

    public Parent getRoot() {
        return root;
    }

    public Button joinButton() {
        return join;
    }

    public String serverUrl() {
        return server.getText().trim();
    }

    public String roomCode() {
        return room.getText().trim();
    }

    public String displayName() {
        return name.getText().trim();
    }
}
