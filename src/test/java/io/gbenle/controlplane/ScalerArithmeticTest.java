package io.gbenle.controlplane;

import io.gbenle.controlplane.predictive.Forecaster;
import io.gbenle.controlplane.predictive.PredictiveScaler;
import io.gbenle.controlplane.predictive.ScalerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScalerArithmeticTest {

    @Test
    @DisplayName("overflowing headroom multiplication does not inflate a small replica requirement")
    void preservesFiniteReplicaRequirement() {
        assertEquals(2, target(1e308, 2.0, 1e308));
        assertEquals(3, target(1e308, 4.0, 1.5e308));
    }

    @Test
    @DisplayName("extreme positive and negative demand still respects replica bounds")
    void clampsExtremeReplicaRequirements() {
        assertEquals(50, target(Double.MAX_VALUE, 2.0, Double.MIN_VALUE));
        assertEquals(1, target(-Double.MAX_VALUE, 2.0, Double.MIN_VALUE));
    }

    @Test
    @DisplayName("ordinary finite calculations retain their established rounding and operation order")
    void preservesOrdinaryRounding() {
        assertEquals(1, target(10.0, 1.1, 11.0));
        assertEquals(4, target(1.0, 2.1, 0.7));
    }

    private static int target(double peak, double safetyFactor, double capacity) {
        Forecaster forecast = new Forecaster() {
            @Override
            public double[] forecast(double[] history, int horizon) {
                double[] predictions = new double[horizon];
                Arrays.fill(predictions, peak);
                return predictions;
            }

            @Override
            public String name() {
                return "fixed";
            }
        };
        ScalerConfig config = new ScalerConfig(capacity, 1, 50, safetyFactor, 1.0,
                Duration.ZERO, Duration.ZERO);
        return new PredictiveScaler(forecast, config)
                .decide(new double[]{peak}, 1, 1, Instant.EPOCH).targetReplicas();
    }
}
