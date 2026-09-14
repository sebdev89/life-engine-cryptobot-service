package io.lifeengine.cryptobot.infrastructure.persistence.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.stereotype.Component;

/** (De)serialises domain records into the JSONB {@code doc} columns. Fails loudly: a bad doc is a bug. */
@Component
public class JsonDocs {

    private final ObjectMapper objectMapper;

    public JsonDocs(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Json write(Object value) {
        try {
            return Json.of(objectMapper.writeValueAsString(value));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialise " + value.getClass().getSimpleName(), e);
        }
    }

    public <T> T read(Json json, Class<T> type) {
        try {
            return objectMapper.readValue(json.asString(), type);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot deserialise " + type.getSimpleName(), e);
        }
    }
}
