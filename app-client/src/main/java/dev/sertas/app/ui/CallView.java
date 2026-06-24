package dev.sertas.app.ui;

import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Экран активного звонка: список участников, mute, выход. */
public final class CallView {

    private final BorderPane root = new BorderPane();
    private final ToggleButton mute = new ToggleButton("Микрофон вкл");
    private final Button leave = new Button("Выйти");

    public CallView(String room, ObservableList<ParticipantModel> participants) {
        Label header = new Label("Комната: " + room);

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

        HBox controls = new HBox(10, mute, leave);
        controls.setPadding(new Insets(10));

        VBox top = new VBox(header);
        top.setPadding(new Insets(10));

        root.setTop(top);
        root.setCenter(table);
        root.setBottom(controls);
    }

    public Parent getRoot() {
        return root;
    }

    public ToggleButton muteButton() {
        return mute;
    }

    public Button leaveButton() {
        return leave;
    }
}
