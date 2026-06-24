package dev.sertas.app;

import dev.sertas.app.ui.JoinView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** Точка входа JavaFX. */
public class SertasApp extends Application {

    @Override
    public void start(Stage stage) {
        stage.setTitle("sertas");
        stage.setScene(new Scene(new JoinView().getRoot(), 420, 280));
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
