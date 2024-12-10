package io.gbenle.controlplane.predictive;

import java.util.Arrays;

/**
 * Exponentially weighted moving average, held flat across the forecast horizon.
 *
 * <p>This is the baseline the seasonal model is measured against. It carries no trend
 * and no seasonality, so it behaves much like the reactive thresholds built into most
 * autoscalers: it tracks where load has been, not where load is going.
 */
public final class EwmaForecaster implements Forecaster {

    private final double alpha;

    /**
     * @param alpha smoothing factor in (0, 1); higher reacts faster and is noisier
     */
    public EwmaForecaster(double alpha) {
        if (!(alpha > 0.0 && alpha < 1.0)) {
            throw new IllegalArgumentException("alpha must be in (0,1), got " + alpha);
        }
        this.alpha = alpha;
    }

    @Override
    public String name() {
        return "ewma(alpha=" + alpha + ")";
    }

    @Override
    public double[] forecast(double[] history, int horizon) {
        if (horizon < 1) {
            throw new IllegalArgumentException("horizon must be >= 1, got " + horizon);
        }
        if (history == null || history.length == 0) {
            throw new IllegalArgumentException("history must contain at least one point");
        }
        for (int i = 0; i < history.length; i++) {
            if (!Double.isFinite(history[i])) {
                throw new IllegalArgumentException("history[" + i + "] must be finite");
            }
        }
        double level = history[0];
        for (int t = 1; t < history.length; t++) {
            level = alpha * history[t] + (1.0 - alpha) * level;
        }
        double[] out = new double[horizon];
        Arrays.fill(out, level);
        return out;
    }
}
