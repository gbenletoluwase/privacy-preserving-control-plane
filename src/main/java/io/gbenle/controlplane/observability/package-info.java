/**
 * Input drift and load anomaly detection.
 *
 * <p>{@link io.gbenle.controlplane.observability.DriftDetector} compares distributions with
 * the population stability index over reference quantile bins.
 * {@link io.gbenle.controlplane.observability.AnomalyMonitor} flags individual points using
 * the median absolute deviation, which a large outlier cannot inflate to hide itself.
 */
package io.gbenle.controlplane.observability;
