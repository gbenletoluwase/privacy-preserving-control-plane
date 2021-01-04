package io.gbenle.controlplane.observability;

/**
 * The result of comparing a current distribution against a reference distribution.
 *
 * @param psi      population stability index, zero when the distributions match
 * @param severity banded interpretation of the index
 * @param bins     number of quantile bins used
 * @param sampleSize number of observations in the current window
 */
public record DriftReport(double psi, DriftReport.Severity severity, int bins, int sampleSize) {

    /**
     * Conventional PSI bands, widely used in credit risk monitoring and carried over
     * to model monitoring. They are heuristics, not thresholds derived from your data,
     * and a team should recalibrate them against its own false-alarm tolerance.
     */
    public enum Severity {
        /** PSI below 0.10. No action. */
        STABLE,
        /** PSI in [0.10, 0.25). Worth investigating. */
        MODERATE,
        /** PSI at or above 0.25. Treat the model's outputs as suspect. */
        SIGNIFICANT
    }

    public static Severity classify(double psi) {
        if (psi < 0.10) {
            return Severity.STABLE;
        }
        if (psi < 0.25) {
            return Severity.MODERATE;
        }
        return Severity.SIGNIFICANT;
    }

    public boolean actionable() {
        return severity != Severity.STABLE;
    }

    @Override
    public String toString() {
        return "PSI %.4f over %d bins on %d samples: %s".formatted(psi, bins, sampleSize, severity);
    }
}
