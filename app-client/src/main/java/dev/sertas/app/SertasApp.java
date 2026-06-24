package dev.sertas.app;

import dev.sertas.app.ui.CallView;
import dev.sertas.app.ui.JoinView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** Точка входа JavaFX: экран входа → экран звонка. */
public class SertasApp extends Application {

    private final CallController controller = new CallController();
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("sertas");
        showJoin();
        stage.show();
    }

    private void showJoin() {
        JoinView join = new JoinView();
        join.joinButton().setOnAction(e -> {
            String url = join.serverUrl();
            String room = join.roomCode();
            String name = join.displayName();
            if (url.isEmpty() || room.isEmpty() || name.isEmpty()) {
                return;
            }
            controller.join(url, room, name);
            showCall(room);
        });
        stage.setScene(new Scene(join.getRoot(), 420, 300));
    }

    private void showCall(String room) {
        CallView call = new CallView(room, controller.participants());
        call.muteButton().selectedProperty().addListener((obs, was, muted) -> {
            controller.setMicMuted(muted);
            call.muteButton().setText(muted ? "Микрофон выкл" : "Микрофон вкл");
        });
        call.leaveButton().setOnAction(e -> {
            controller.leave();
            showJoin();
        });
        stage.setScene(new Scene(call.getRoot(), 420, 360));
    }

    @Override
    public void stop() {
        controller.leave();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
