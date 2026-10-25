package com.fraternity.dues;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Cent handling for stored money values. Every model setter rounds through {@link #cents(double)}, so repeated
 * arithmetic (1.10 - 0.90 - 0.20) can't leave a fraction of a cent behind that makes a paid-up account look unpaid.
 * Rounding in the setters also cleans up such leftovers in existing saves when they are loaded.
 */
public final class Money {
    /** Half a cent: anything smaller is treated as zero. */
    public static final double HALF_CENT = 0.005;

    private Money() {}

    /** Rounds to the nearest cent (half up). Non-finite values pass through so validation can still reject them. */
    public static double cents(double value) {
        if (!Double.isFinite(value)) return value;
        double rounded = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
        return rounded == 0 ? 0.0 : rounded; // also turns -0.0 into 0.0
    }

    /** True when the value is at least a cent, i.e. something is actually owed. */
    public static boolean positive(double value) {
        return value >= HALF_CENT;
    }
}
