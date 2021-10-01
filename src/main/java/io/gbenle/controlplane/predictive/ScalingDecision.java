package io.gbenle.controlplane.predictive;

import java.time.Instant;

/**
 * The outcome of one scaling evaluation, including the reasoning behind it.
 *
 * <p>The rationale is not decoration. An autoscaler that changes capacity without
 * leaving a readable reason is impossible to debug at three in the morning, and
 * impossible to defend in a review.
 *
 * @param decidedAt        when the evaluation ran
 * @param currentReplicas  replica count at evaluation time
 * @param targetReplicas   replica count the scaler is asking for
 * @param predictedPeak    highest predicted load across the horizon
 * @param forecaster       identifier of the model that produced the prediction
 * @param rationale        human-readable explanation of the decision
 */
public record ScalingDecision(
        Instant decidedAt,
        int currentReplicas,
        int targetReplicas,
        double predictedPeak,
        String forecaster,
        String rationale) {

    /** True when the target differs from the current replica count. */
    public boolean changesCapacity() {
        return targetReplicas != currentReplicas;
    }

    /** Signed change in replicas, negative when scaling in. */
    public int delta() {
        return targetReplicas - currentReplicas;
    }

    @Override
    public String toString() {
        return "%s replicas %d -> %d (peak %.1f, %s): %s"
                .formatted(decidedAt, currentReplicas, targetReplicas,
                        predictedPeak, forecaster, rationale);
    }
}
