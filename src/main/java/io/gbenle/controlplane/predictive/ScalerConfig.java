package io.gbenle.controlplane.predictive;

import java.time.Duration;
import java.util.Objects;

/**
 * Capacity limits and safety margins for {@link PredictiveScaler}.
 *
 * @param capacityPerReplica finite, positive units of load one replica can absorb
 * @param minReplicas        floor, never scaled below
 * @param maxReplicas        ceiling, never scaled above
 * @param safetyFactor       finite multiplier of at least 1; 1.2 provisions 20 percent above forecast
 * @param scaleDownDampening largest fraction of current capacity that may be removed in one step
 * @param scaleUpCooldown    non-null, nonnegative interval between scale-ups; zero disables the cooldown
 * @param scaleDownCooldown  non-null, nonnegative interval between scale-downs; zero disables the cooldown
 */
public record ScalerConfig(
        double capacityPerReplica,
        int minReplicas,
        int maxReplicas,
        double safetyFactor,
        double scaleDownDampening,
        Duration scaleUpCooldown,
        Duration scaleDownCooldown) {

    public ScalerConfig {
        if (!Double.isFinite(capacityPerReplica) || capacityPerReplica <= 0) {
            throw new IllegalArgumentException("capacityPerReplica must be finite and > 0");
        }
        if (minReplicas < 1) {
            throw new IllegalArgumentException("minReplicas must be >= 1");
        }
        if (maxReplicas < minReplicas) {
            throw new IllegalArgumentException("maxReplicas must be >= minReplicas");
        }
        if (!Double.isFinite(safetyFactor) || safetyFactor < 1.0) {
            throw new IllegalArgumentException("safetyFactor must be finite and >= 1.0");
        }
        if (!(scaleDownDampening > 0.0 && scaleDownDampening <= 1.0)) {
            throw new IllegalArgumentException("scaleDownDampening must be in (0,1]");
        }
        Objects.requireNonNull(scaleUpCooldown, "scaleUpCooldown");
        Objects.requireNonNull(scaleDownCooldown, "scaleDownCooldown");
        if (scaleUpCooldown.isNegative()) {
            throw new IllegalArgumentException("scaleUpCooldown must be nonnegative");
        }
        if (scaleDownCooldown.isNegative()) {
            throw new IllegalArgumentException("scaleDownCooldown must be nonnegative");
        }
    }

    /**
     * Conservative defaults: 20 percent headroom, never shed more than a third of
     * capacity at once, scale in no more often than every ten minutes.
     */
    public static ScalerConfig defaults(double capacityPerReplica, int min, int max) {
        return new ScalerConfig(
                capacityPerReplica, min, max,
                1.2, 0.34,
                Duration.ofMinutes(2), Duration.ofMinutes(10));
    }
}
