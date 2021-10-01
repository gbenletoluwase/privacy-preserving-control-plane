package io.gbenle.controlplane.observability;

import java.util.Arrays;

/**
 * Detects distribution shift with the population stability index.
 *
 * <p>A model does not usually fail by crashing. It fails by continuing to answer
 * confidently while the data underneath it moves away from what it was trained on, and
 * nothing in the serving path notices because every request still returns 200. PSI
 * compares the shape of a recent window against a fixed reference window:
 *
 * <pre>
 *   PSI = sum over bins of (actual% - expected%) * ln(actual% / expected%)
 * </pre>
 *
 * <p>Bins are quantiles of the reference sample rather than equal-width intervals, so
 * the measure does not become meaningless on skewed data. Empty bins are floored at a
 * small epsilon, because the logarithm is undefined at zero and an unseen bin should
 * contribute a large but finite amount of evidence rather than an infinity.
 *
 * <p>This class holds no state; the reference sample is passed in on each call so the
 * caller controls how and when the baseline is refreshed.
 */
public final class DriftDetector {

    private static final double EPSILON = 1e-6;

    private final int bins;

    public DriftDetector(int bins) {
        if (bins < 2) {
            throw new IllegalArgumentException("bins must be >= 2, got " + bins);
        }
        this.bins = bins;
    }

    /** Ten quantile bins, the common default. */
    public static DriftDetector deciles() {
        return new DriftDetector(10);
    }

    /**
     * @param reference the baseline sample, typically the training distribution
     * @param current   the recent window to test
     */
    public DriftReport compare(double[] reference, double[] current) {
        if (reference == null || reference.length < bins) {
            throw new IllegalArgumentException(
                    "reference needs at least " + bins + " observations");
        }
        if (current == null || current.length == 0) {
            throw new IllegalArgumentException("current window must not be empty");
        }

        double[] edges = quantileEdges(reference, bins);
        double[] expected = proportions(reference, edges);
        double[] actual = proportions(current, edges);

        double psi = 0.0;
        for (int i = 0; i < expected.length; i++) {
            double e = Math.max(expected[i], EPSILON);
            double a = Math.max(actual[i], EPSILON);
            psi += (a - e) * Math.log(a / e);
        }
        return new DriftReport(psi, DriftReport.classify(psi), bins, current.length);
    }

    /** Interior cut points of the reference distribution; the array has {@code bins - 1} entries. */
    private static double[] quantileEdges(double[] reference, int bins) {
        double[] sorted = reference.clone();
        Arrays.sort(sorted);
        double[] edges = new double[bins - 1];
        for (int i = 1; i < bins; i++) {
            double position = (double) i / bins * (sorted.length - 1);
            int lower = (int) Math.floor(position);
            int upper = (int) Math.ceil(position);
            double fraction = position - lower;
            edges[i - 1] = sorted[lower] + fraction * (sorted[upper] - sorted[lower]);
        }
        return edges;
    }

    private static double[] proportions(double[] values, double[] edges) {
        int[] counts = new int[edges.length + 1];
        for (double value : values) {
            counts[binOf(value, edges)]++;
        }
        double[] out = new double[counts.length];
        for (int i = 0; i < counts.length; i++) {
            out[i] = (double) counts[i] / values.length;
        }
        return out;
    }

    private static int binOf(double value, double[] edges) {
        int index = Arrays.binarySearch(edges, value);
        return index >= 0 ? index + 1 : -(index + 1);
    }
}
