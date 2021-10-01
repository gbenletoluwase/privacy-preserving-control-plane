package io.gbenle.controlplane;

import io.gbenle.controlplane.predictive.Forecaster;
import io.gbenle.controlplane.predictive.PredictiveScaler;
import io.gbenle.controlplane.predictive.ScalerConfig;
import io.gbenle.controlplane.predictive.ScalingDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictiveScalerTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** A forecaster that returns whatever the test tells it to, so the scaler is tested alone. */
    private record FixedForecaster(double value) implements Forecaster {
        @Override
        public double[] forecast(double[] history, int horizon) {
            double[] out = new double[horizon];
            Arrays.fill(out, value);
            return out;
        }

        @Override
        public String name() {
            return "fixed(" + value + ")";
        }
    }

    private static ScalerConfig config() {
        return new ScalerConfig(100.0, 2, 50, 1.2, 0.34,
                Duration.ofMinutes(2), Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("provisions for predicted peak plus the safety margin")
    void scalesUpForPredictedPeak() {
        PredictiveScaler scaler = new PredictiveScaler(new FixedForecaster(1000.0), config());

        ScalingDecision decision = scaler.decide(new double[]{1, 2, 3}, 4, 6, T0);

        assertEquals(12, decision.targetReplicas(), "1000 * 1.2 / 100 = 12");
        assertTrue(decision.changesCapacity());
        assertEquals(8, decision.delta());
    }

    @Test
    @DisplayName("honours the ceiling and the floor")
    void respectsBounds() {
        ScalingDecision high = new PredictiveScaler(new FixedForecaster(99_999.0), config())
                .decide(new double[]{1}, 10, 3, T0);
        assertEquals(50, high.targetReplicas());

        ScalingDecision low = new PredictiveScaler(new FixedForecaster(1.0), config())
                .decide(new double[]{1}, 2, 3, T0);
        assertEquals(2, low.targetReplicas());
    }

    @Test
    @DisplayName("will not shed more than the dampening fraction in one step")
    void dampensScaleIn() {
        PredictiveScaler scaler = new PredictiveScaler(new FixedForecaster(100.0), config());

        ScalingDecision decision = scaler.decide(new double[]{1}, 30, 3, T0);

        assertEquals(20, decision.targetReplicas(),
                "unconstrained target is 2, dampening floors it at ceil(30 * 0.66) = 20");
        assertTrue(decision.rationale().contains("dampened"));
    }

    @Test
    @DisplayName("holds capacity while the scale-up cooldown is active, then acts once it elapses")
    void respectsScaleUpCooldown() {
        PredictiveScaler scaler = new PredictiveScaler(new FixedForecaster(1000.0), config());

        ScalingDecision first = scaler.decide(new double[]{1}, 4, 3, T0);
        assertEquals(12, first.targetReplicas());

        ScalingDecision tooSoon = scaler.decide(new double[]{1}, 6, 3, T0.plusSeconds(30));
        assertEquals(6, tooSoon.targetReplicas(), "cooldown should pin capacity to current");
        assertFalse(tooSoon.changesCapacity());
        assertTrue(tooSoon.rationale().contains("cooldown"));

        ScalingDecision later = scaler.decide(new double[]{1}, 6, 3, T0.plus(Duration.ofMinutes(5)));
        assertEquals(12, later.targetReplicas(), "cooldown has elapsed, the scaler may act");
    }

    @Test
    @DisplayName("records which model produced the prediction")
    void recordsForecasterIdentity() {
        ScalingDecision decision = new PredictiveScaler(new FixedForecaster(500.0), config())
                .decide(new double[]{1}, 6, 3, T0);
        assertEquals("fixed(500.0)", decision.forecaster());
        assertEquals(500.0, decision.predictedPeak());
    }
}
