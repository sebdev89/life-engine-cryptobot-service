package io.lifeengine.cryptobot.proofofvalue;

/**
 * DistributionPolicy V1: {@code pov/equal-split/v1} — {@link #TOTAL_UNITS} units split
 * in equal parts among the contributions, the remainder to the first contribution (request order).
 * Predefined, auditable, deterministic: no model decides who gets what.
 */
public final class DistributionPolicy {

    public static final String EQUAL_SPLIT_V1 = "pov/equal-split/v1";
    public static final int TOTAL_UNITS = 100;

    private DistributionPolicy() {}

    public static boolean isSupported(String policy) {
        return policy == null || policy.isBlank() || EQUAL_SPLIT_V1.equals(policy.trim());
    }

    /** Units per contribution, in order; they always add up to {@link #TOTAL_UNITS}. */
    public static int[] equalSplit(int contributions) {
        if (contributions < 1) {
            throw new IllegalArgumentException("at least one contribution is required");
        }
        int[] units = new int[contributions];
        int share = TOTAL_UNITS / contributions;
        java.util.Arrays.fill(units, share);
        units[0] += TOTAL_UNITS - share * contributions;
        return units;
    }
}
