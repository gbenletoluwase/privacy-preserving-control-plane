/**
 * Forecast-driven autoscaling.
 *
 * <p>{@link io.gbenle.controlplane.predictive.PredictiveScaler} sizes a fleet from the
 * predicted peak across a horizon, produced by any
 * {@link io.gbenle.controlplane.predictive.Forecaster}. The scaler guards against
 * forecast-induced instability with a safety factor, scale-in dampening and asymmetric
 * cooldowns, and explains every {@link io.gbenle.controlplane.predictive.ScalingDecision}.
 */
package io.gbenle.controlplane.predictive;
