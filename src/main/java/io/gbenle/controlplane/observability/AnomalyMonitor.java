package io.gbenle.controlplane.observability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Flags individual observations that are far from the body of a series.
 *
 * <p>The usual approach, a z-score against the mean and standard deviation, has a flaw
 * that matters in exactly the situation you care about: the outlier you are hunting is
 * itself inflating both statistics, so a large enough spike hides itself. This monitor
 * uses the median and the median absolute deviation instead, which a minority of
 * extreme points cannot move. The MAD is scaled by 1.4826 so that for normally
 * distributed data the resulting score is comparable to a conventional z-score.
 *
 * <p>When the MAD is zero, meaning more than half the series is identical, the monitor
 * falls back to the standard deviation; when that is zero too, the series is constant
 * and nothing is reported.
 */
public final class AnomalyMonitor {

    private static final double MAD_TO_SIGMA = 1.4826;

    /**
     * One flagged observation.
     *
     * @param index position in the input series
     * @param value the observed value
     * @param score robust deviation score, in units comparable to standard deviations
     */
    public record Anomaly(int index, double value, double score) {
        @Override
        public String toString() {
            return "index %d value %.3f score %.2f".formatted(index, value, score);
        }
    }

    private final double threshold;

    /**
     * @param threshold score above which an observation is reported; 3.5 is a common choice
     * @throws IllegalArgumentException if the threshold is not positive and finite
     */
    public AnomalyMonitor(double threshold) {
        if (!Double.isFinite(threshold) || !(threshold > 0.0)) {
            throw new IllegalArgumentException("threshold must be finite and > 0");
        }
        this.threshold = threshold;
    }

    public static AnomalyMonitor standard() {
        return new AnomalyMonitor(3.5);
    }

    /**
     * Returns no anomalies for a missing series or fewer than three finite observations.
     * Non-null series are validated even when too short to score, so invalid observations
     * cannot be mistaken for a healthy series.
     *
     * @throws IllegalArgumentException if any observation is not finite
     */
    public List<Anomaly> scan(double[] series) {
        if (series == null) {
            return List.of();
        }
        for (int i = 0; i < series.length; i++) {
            if (!Double.isFinite(series[i])) {
                throw new IllegalArgumentException("series observation at index " + i + " must be finite");
            }
        }
        if (series.length < 3) {
            return List.of();
        }
        double median = median(series.clone());

        double[] deviations = new double[series.length];
        for (int i = 0; i < series.length; i++) {
            deviations[i] = Math.abs(series[i] - median);
        }
        double scale = median(deviations) * MAD_TO_SIGMA;

        if (scale == 0.0) {
            scale = standardDeviation(series);
        }
        if (scale == 0.0) {
            return List.of();
        }

        List<Anomaly> found = new ArrayList<>();
        for (int i = 0; i < series.length; i++) {
            double score = Math.abs(series[i] - median) / scale;
            if (score > threshold) {
                found.add(new Anomaly(i, series[i], score));
            }
        }
        return List.copyOf(found);
    }

    /** Sorts the array in place and returns its median. */
    private static double median(double[] values) {
        Arrays.sort(values);
        int n = values.length;
        return (n % 2 == 1)
                ? values[n / 2]
                : (values[n / 2 - 1] + values[n / 2]) / 2.0;
    }

    private static double standardDeviation(double[] values) {
        double mean = Arrays.stream(values).average().orElse(0.0);
        double sumSquares = 0.0;
        for (double value : values) {
            double d = value - mean;
            sumSquares += d * d;
        }
        return Math.sqrt(sumSquares / values.length);
    }
}
