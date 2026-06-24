package dev.sertas.app.ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** JavaFX-модель строки участника в сетке звонка. */
public final class ParticipantModel {

    private final String id;
    private final StringProperty name = new SimpleStringProperty();
    private final StringProperty state = new SimpleStringProperty("…");

    public ParticipantModel(String id, String name) {
        this.id = id;
        this.name.set(name);
    }

    public String id() {
        return id;
    }

    public StringProperty nameProperty() {
        return name;
    }

    public StringProperty stateProperty() {
        return state;
    }

    public void setName(String value) {
        name.set(value);
    }

    public void setState(String value) {
        state.set(value);
    }
}
