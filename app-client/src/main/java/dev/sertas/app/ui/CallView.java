package dev.sertas.app.ui;

import javafx.beans.binding.Bindings;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Экран активного звонка: видео-плитки, список участников, mute, демонстрация, выход. */
public final class CallView {

    private final BorderPane root = new BorderPane();
    private final ToggleButton mute = new ToggleButton("Микрофон вкл");
    private final ToggleButton share = new ToggleButton("Демонстрация");
    private final Button leave = new Button("Выйти");

    public CallView(String room,
                    ObservableList<ParticipantModel> participants,
                    ObservableList<Node> videoTiles) {
        Label header = new Label("Комната: " + room);

        FlowPane videoPane = new FlowPane(10, 10);
        videoPane.setPadding(new Insets(10));
        Bindings.bindContent(videoPane.getChildren(), videoTiles);

        TableView<ParticipantModel> table = new TableView<>();
        TableColumn<ParticipantModel, String> nameCol = new TableColumn<>("Участник");
        nameCol.setCellValueFactory(c -> c.getValue().nameProperty());
        nameCol.setPrefWidth(240);
        TableColumn<ParticipantModel, String> stateCol = new TableColumn<>("Статус");
        stateCol.setCellValueFactory(c -> c.getValue().stateProperty());
        stateCol.setPrefWidth(150);
        table.getColumns().add(nameCol);
        table.getColumns().add(stateCol);
        table.setItems(participants);
        table.setPlaceholder(new Label("Ожидание участников…"));
        table.setPrefHeight(160);

        VBox center = new VBox(8, videoPane, table);
        VBox.setVgrow(videoPane, Priority.ALWAYS);

        HBox controls = new HBox(10, mute, share, leave);
        controls.setPadding(new Insets(10));

        VBox top = new VBox(header);
        top.setPadding(new Insets(10));

        root.setTop(top);
        root.setCenter(center);
        root.setBottom(controls);
    }

    public Parent getRoot() {
        return root;
    }

    public ToggleButton muteButton() {
        return mute;
    }

    public ToggleButton shareButton() {
        return share;
    }

    public Button leaveButton() {
        return leave;
    }
}
