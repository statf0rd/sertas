package dev.sertas.app.ui;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/** JavaFX-модель строки участника в сетке звонка. */
public final class ParticipantModel {

    private final String id;
    private final StringProperty name = new SimpleStringProperty();
    private final StringProperty state = new SimpleStringProperty("…");
    /** Громкость голоса и звука демо этого участника у слушателя (1.0 = 100%). */
    private final DoubleProperty voiceGain = new SimpleDoubleProperty(1.0);
    private final DoubleProperty demoGain = new SimpleDoubleProperty(1.0);

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

    public DoubleProperty voiceGainProperty() {
        return voiceGain;
    }

    public DoubleProperty demoGainProperty() {
        return demoGain;
    }

    public void setName(String value) {
        name.set(value);
    }

    public void setState(String value) {
        state.set(value);
    }
}
