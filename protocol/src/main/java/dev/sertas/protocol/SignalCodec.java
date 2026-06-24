package dev.sertas.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JSON-кодек сообщений сигналинга. Потокобезопасен (ObjectMapper). */
public final class SignalCodec {

    private final ObjectMapper mapper = new ObjectMapper();

    public String encode(SignalMessage msg) {
        try {
            return mapper.writeValueAsString(msg);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot encode signal: " + msg, e);
        }
    }

    public SignalMessage decode(String json) {
        try {
            return mapper.readValue(json, SignalMessage.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot decode signal: " + json, e);
        }
    }
}
