package io.lifeengine.cryptobot.proofofvalue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AcceptancePolicy V1 (KAN-818): a contribution is value only once the outcome went all the way
 * {@code MERGED → BUILT → DEPLOYED → RUNNING → ACCEPTED} (P-16: a merged PR is not a delivered
 * outcome). Every stage must be present and {@code true}; anything else is a list of violations,
 * one per stage, that the API returns as a 422.
 */
public final class AcceptancePolicy {

    public static final String ID = "pov/acceptance/v1";
    public static final List<String> STAGES = List.of("MERGED", "BUILT", "DEPLOYED", "RUNNING", "ACCEPTED");

    private AcceptancePolicy() {}

    /** Empty when the five stages are all {@code true}. */
    public static List<String> violations(Map<String, Boolean> stages) {
        List<String> out = new ArrayList<>();
        for (String stage : STAGES) {
            Boolean v = stages == null ? null : stages.get(stage);
            if (v == null) {
                out.add(stage + ": missing");
            } else if (!v) {
                out.add(stage + ": false");
            }
        }
        return out;
    }
}
