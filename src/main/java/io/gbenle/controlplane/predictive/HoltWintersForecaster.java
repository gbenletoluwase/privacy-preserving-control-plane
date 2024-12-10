package io.gbenle.controlplane.predictive;

/**
 * Additive Holt-Winters triple exponential smoothing.
 *
 * <p>Load on a shared data pipeline is rarely random. It has a level, a trend, and a
 * daily or weekly shape driven by human working hours. This model carries all three,
 * which is what lets the scaler provision ahead of a known morning ramp instead of
 * discovering it once latency has already risen.
 *
 * <p>The recurrences are the standard additive form:
 * <pre>
 *   level_t    = alpha * (y_t - season_{t-m}) + (1 - alpha) * (level_{t-1} + trend_{t-1})
 *   trend_t    = beta  * (level_t - level_{t-1}) + (1 - beta) * trend_{t-1}
 *   season_t   = gamma * (y_t - level_t) + (1 - gamma) * season_{t-m}
 *   forecast_h = level_t + h * trend_t + season_{t+h-m}
 * </pre>
 *
 * <p>Instances are immutable and safe to share between threads.
 */
public final class HoltWintersForecaster implements Forecaster {

    private final double alpha;
    private final double beta;
    private final double gamma;
    private final int seasonLength;

    /**
     * @param alpha        level smoothing, in (0, 1)
     * @param beta         trend smoothing, in (0, 1)
     * @param gamma        seasonal smoothing, in (0, 1)
     * @param seasonLength observations per season, at least 2
     */
    public HoltWintersForecaster(double alpha, double beta, double gamma, int seasonLength) {
        this.alpha = requireUnitInterval(alpha, "alpha");
        this.beta = requireUnitInterval(beta, "beta");
        this.gamma = requireUnitInterval(gamma, "gamma");
        if (seasonLength < 2) {
            throw new IllegalArgumentException("seasonLength must be >= 2, got " + seasonLength);
        }
        this.seasonLength = seasonLength;
    }

    /** Hourly observations with a 24-point daily season, a reasonable starting point. */
    public static HoltWintersForecaster hourlyWithDailySeason() {
        return new HoltWintersForecaster(0.35, 0.05, 0.25, 24);
    }

    private static double requireUnitInterval(double v, String label) {
        if (!(v > 0.0 && v < 1.0)) {
            throw new IllegalArgumentException(label + " must be in (0,1), got " + v);
        }
        return v;
    }

    /** Minimum number of observations this model needs, namely two full seasons. */
    public int minimumHistory() {
        return 2 * seasonLength;
    }

    @Override
    public String name() {
        return "holt-winters-additive(m=" + seasonLength + ")";
    }

    @Override
    public double[] forecast(double[] history, int horizon) {
        if (horizon < 1) {
            throw new IllegalArgumentException("horizon must be >= 1, got " + horizon);
        }
        if (history == null || history.length < minimumHistory()) {
            throw new IllegalArgumentException(
                    "history must cover at least two full seasons ("
                            + minimumHistory() + " points), got "
                            + (history == null ? 0 : history.length));
        }
        for (int i = 0; i < history.length; i++) {
            if (!Double.isFinite(history[i])) {
                throw new IllegalArgumentException("history[" + i + "] must be finite");
            }
        }

        final int n = history.length;
        final int m = seasonLength;

        double level = mean(history, 0, m);
        double trend = (mean(history, m, 2 * m) - mean(history, 0, m)) / m;

        double[] season = new double[n];
        for (int i = 0; i < m; i++) {
            season[i] = history[i] - level;
        }

        for (int t = m; t < n; t++) {
            double previousLevel = level;
            level = alpha * (history[t] - season[t - m]) + (1.0 - alpha) * (previousLevel + trend);
            trend = beta * (level - previousLevel) + (1.0 - beta) * trend;
            season[t] = gamma * (history[t] - level) + (1.0 - gamma) * season[t - m];
        }

        double[] out = new double[horizon];
        for (int h = 1; h <= horizon; h++) {
            out[h - 1] = level + h * trend + season[n - m + ((h - 1) % m)];
        }
        return out;
    }

    private static double mean(double[] values, int fromInclusive, int toExclusive) {
        double sum = 0.0;
        for (int i = fromInclusive; i < toExclusive; i++) {
            sum += values[i];
        }
        return sum / (toExclusive - fromInclusive);
    }
}
