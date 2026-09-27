package io.lifeengine.cryptobot.core.intent;

/**
 * The structured data does not fit the intent schema: unknown action, unknown field, missing
 * required field, wrong type, out-of-range value. Fail-closed (paper §17): an ambiguous or
 * malformed intent is not "repaired", it is refused with the field that failed.
 */
public class IntentSchemaViolation extends IllegalArgumentException {

    private final String field;

    public IntentSchemaViolation(String field, String message) {
        super(field + ": " + message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
