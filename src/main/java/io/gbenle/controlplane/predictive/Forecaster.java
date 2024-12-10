package io.gbenle.controlplane.predictive;

/**
 * A univariate time-series forecaster.
 *
 * <p>Implementations are given a history of evenly spaced observations and asked for
 * the next {@code horizon} values. The contract is deliberately narrow so that a
 * statistical model, a gradient-boosted model, or a remote inference service can all
 * sit behind the same interface without the scaler knowing the difference.
 */
public interface Forecaster {

    /**
     * Predicts the next {@code horizon} observations.
     *
     * @param history finite, evenly spaced observations, oldest first
     * @param horizon number of future points to predict, at least 1
     * @return an array of length {@code horizon}, earliest prediction first
     * @throws IllegalArgumentException if the history is null, too short for the model,
     *                                  contains a non-finite observation, or the horizon is less than 1
     */
    double[] forecast(double[] history, int horizon);

    /** A stable identifier for this model, recorded in scaling decisions and audit logs. */
    String name();
}
