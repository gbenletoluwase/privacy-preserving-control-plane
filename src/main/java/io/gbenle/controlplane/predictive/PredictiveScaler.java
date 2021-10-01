package io.gbenle.controlplane.predictive;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Decides replica counts from a forecast rather than from current utilization.
 *
 * <p>A reactive autoscaler observes that load has already exceeded a threshold and
 * then adds capacity, which means the system is under-provisioned for exactly as long
 * as it takes to schedule and warm a new replica. For a pipeline whose replicas carry
 * a model that takes a minute to load, that window is where the backlog accumulates.
 * This scaler moves the decision earlier by acting on predicted peak load across a
 * horizon, with three guards against the instability that prediction invites:
 *
 * <ul>
 *   <li>a safety factor, so a forecast that is slightly low does not immediately hurt;</li>
 *   <li>dampening, so a single quiet interval cannot shed most of the fleet;</li>
 *   <li>asymmetric cooldowns, scaling out eagerly and scaling in reluctantly.</li>
 * </ul>
 *
 * <p>This class keeps the timestamps of the last scale-up and scale-down, so instances
 * are stateful. Methods are synchronized; one instance per scaled workload is intended.
 */
public final class PredictiveScaler {

    private final Forecaster forecaster;
    private final ScalerConfig config;

    private Instant lastScaleUp = Instant.EPOCH;
    private Instant lastScaleDown = Instant.EPOCH;

    public PredictiveScaler(Forecaster forecaster, ScalerConfig config) {
        this.forecaster = Objects.requireNonNull(forecaster, "forecaster");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Evaluates one scaling decision.
     *
     * @param loadHistory     evenly spaced load observations, oldest first
     * @param currentReplicas replicas currently running
     * @param horizon         how many intervals ahead to protect against
     * @param now             evaluation time, supplied rather than read so this is testable
     */
    public synchronized ScalingDecision decide(
            double[] loadHistory, int currentReplicas, int horizon, Instant now) {

        Objects.requireNonNull(now, "now");
        if (currentReplicas < 0) {
            throw new IllegalArgumentException("currentReplicas must be >= 0");
        }

        double[] predicted = forecaster.forecast(loadHistory, horizon);
        double peak = Arrays.stream(predicted).max().orElseThrow();

        int required = (int) Math.ceil((peak * config.safetyFactor()) / config.capacityPerReplica());
        int target = clamp(required);

        if (target > currentReplicas) {
            if (now.isBefore(lastScaleUp.plus(config.scaleUpCooldown()))) {
                return hold(now, currentReplicas, peak, "scale-up cooldown active, holding at "
                        + currentReplicas + " (wanted " + target + ")");
            }
            lastScaleUp = now;
            return new ScalingDecision(now, currentReplicas, target, peak, forecaster.name(),
                    "predicted peak %.1f needs %d replicas at %.1f per replica with %.0f%% headroom"
                            .formatted(peak, target, config.capacityPerReplica(),
                                    (config.safetyFactor() - 1.0) * 100.0));
        }

        if (target < currentReplicas) {
            if (now.isBefore(lastScaleDown.plus(config.scaleDownCooldown()))) {
                return hold(now, currentReplicas, peak, "scale-down cooldown active, holding at "
                        + currentReplicas + " (wanted " + target + ")");
            }
            int floor = (int) Math.ceil(currentReplicas * (1.0 - config.scaleDownDampening()));
            int dampened = clamp(Math.max(target, floor));
            if (dampened == currentReplicas) {
                return hold(now, currentReplicas, peak,
                        "dampening prevents removing more than %.0f%% of capacity in one step"
                                .formatted(config.scaleDownDampening() * 100.0));
            }
            lastScaleDown = now;
            String note = dampened > target
                    ? "scale-in dampened from %d to %d".formatted(target, dampened)
                    : "predicted peak %.1f no longer needs %d replicas".formatted(peak, currentReplicas);
            return new ScalingDecision(now, currentReplicas, dampened, peak, forecaster.name(), note);
        }

        return hold(now, currentReplicas, peak, "forecast matches current capacity");
    }

    private ScalingDecision hold(Instant now, int current, double peak, String why) {
        return new ScalingDecision(now, current, current, peak, forecaster.name(), why);
    }

    private int clamp(int replicas) {
        return Math.max(config.minReplicas(), Math.min(config.maxReplicas(), replicas));
    }
}
