package io.gbenle.controlplane;

import io.gbenle.controlplane.predictive.Forecaster;
import io.gbenle.controlplane.predictive.PredictiveScaler;
import io.gbenle.controlplane.predictive.ScalerConfig;
import io.gbenle.controlplane.predictive.ScalingDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /** Mutable test stub; each instance is used only by its owning test thread. */
    private static final class StubForecaster implements Forecaster {
        private double[] prediction;
        private int calls;

        private StubForecaster(double[] prediction) {
            this.prediction = prediction;
        }

        @Override
        public double[] forecast(double[] history, int horizon) {
            calls++;
            return prediction;
        }

        @Override
        public String name() {
            return "stub";
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

    @ParameterizedTest
    @ValueSource(strings = {"1969-12-31T23:59:00Z", "1970-01-01T00:00:00Z", "1970-01-01T00:00:30Z"})
    @DisplayName("the first scale-up has no cooldown regardless of the evaluation date")
    void initialScaleUpHasNoCooldown(String timestamp) {
        PredictiveScaler scaler = new PredictiveScaler(new FixedForecaster(1000.0), config());

        ScalingDecision decision = scaler.decide(new double[]{1}, 4, 3, Instant.parse(timestamp));

        assertEquals(12, decision.targetReplicas());
        assertTrue(decision.changesCapacity());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1969-12-31T23:59:00Z", "1970-01-01T00:00:00Z", "1970-01-01T00:00:30Z"})
    @DisplayName("the first scale-down has no cooldown regardless of the evaluation date")
    void initialScaleDownHasNoCooldown(String timestamp) {
        PredictiveScaler scaler = new PredictiveScaler(new FixedForecaster(100.0), config());

        ScalingDecision decision = scaler.decide(new double[]{1}, 30, 3, Instant.parse(timestamp));

        assertEquals(20, decision.targetReplicas());
        assertTrue(decision.changesCapacity());
    }

    @Test
    @DisplayName("a scale-up at the epoch starts a cooldown that expires at its exact boundary")
    void respectsScaleUpCooldownAtEpoch() {
        PredictiveScaler scaler = new PredictiveScaler(new FixedForecaster(1000.0), config());

        ScalingDecision first = scaler.decide(new double[]{1}, 4, 3, Instant.EPOCH);
        assertEquals(12, first.targetReplicas());

        Instant expiry = Instant.EPOCH.plus(config().scaleUpCooldown());
        ScalingDecision tooSoon = scaler.decide(new double[]{1}, 6, 3, expiry.minusNanos(1));
        assertEquals(6, tooSoon.targetReplicas(), "cooldown should pin capacity to current");
        assertFalse(tooSoon.changesCapacity());
        assertTrue(tooSoon.rationale().contains("cooldown"));

        ScalingDecision later = scaler.decide(new double[]{1}, 6, 3, expiry);
        assertEquals(12, later.targetReplicas(), "cooldown has elapsed, the scaler may act");
    }

    @Test
    @DisplayName("a scale-down at the epoch starts a cooldown that expires at its exact boundary")
    void respectsScaleDownCooldownAtEpoch() {
        PredictiveScaler scaler = new PredictiveScaler(new FixedForecaster(100.0), config());

        ScalingDecision first = scaler.decide(new double[]{1}, 30, 3, Instant.EPOCH);
        assertEquals(20, first.targetReplicas());

        Instant expiry = Instant.EPOCH.plus(config().scaleDownCooldown());
        ScalingDecision tooSoon = scaler.decide(new double[]{1}, 20, 3, expiry.minusNanos(1));
        assertEquals(20, tooSoon.targetReplicas());
        assertFalse(tooSoon.changesCapacity());

        ScalingDecision atExpiry = scaler.decide(new double[]{1}, 20, 3, expiry);
        assertEquals(14, atExpiry.targetReplicas());
        assertTrue(atExpiry.changesCapacity());
    }

    @Test
    @DisplayName("records which model produced the prediction")
    void recordsForecasterIdentity() {
        ScalingDecision decision = new PredictiveScaler(new FixedForecaster(500.0), config())
                .decide(new double[]{1}, 6, 3, T0);
        assertEquals("fixed(500.0)", decision.forecaster());
        assertEquals(500.0, decision.predictedPeak());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("a nonpositive horizon is rejected before the forecaster is invoked")
    void rejectsInvalidHorizonBeforeForecasting(int horizon) {
        StubForecaster forecaster = new StubForecaster(new double[]{1000.0});
        PredictiveScaler scaler = new PredictiveScaler(forecaster, config());

        assertThrows(IllegalArgumentException.class,
                () -> scaler.decide(new double[]{1}, 4, horizon, T0));

        assertEquals(0, forecaster.calls);
    }

    private static Stream<Arguments> malformedForecasts() {
        return Stream.of(
                Arguments.of("null", null),
                Arguments.of("empty", new double[0]),
                Arguments.of("short", new double[]{1000.0, 1000.0}),
                Arguments.of("long", new double[]{1000.0, 1000.0, 1000.0, 1000.0}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedForecasts")
    @DisplayName("malformed forecast arrays fail before starting either scaling cooldown")
    void rejectsMalformedForecasts(String description, double[] prediction) {
        assertInvalidForecastDoesNotStartCooldown(prediction);
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    @DisplayName("non-finite values in any forecast position fail without starting either cooldown")
    void rejectsNonFiniteForecasts(double invalid) {
        for (int i = 0; i < 3; i++) {
            double[] prediction = {1000.0, 1000.0, 1000.0};
            prediction[i] = invalid;
            assertInvalidForecastDoesNotStartCooldown(prediction);
        }
    }

    private static void assertInvalidForecastDoesNotStartCooldown(double[] prediction) {
        for (boolean scalingUp : new boolean[]{true, false}) {
            int current = scalingUp ? 4 : 30;
            StubForecaster forecaster = new StubForecaster(prediction);
            PredictiveScaler scaler = new PredictiveScaler(forecaster, config());
            assertThrows(IllegalStateException.class,
                    () -> scaler.decide(new double[]{1}, current, 3, T0));

            double valid = scalingUp ? 1000.0 : 100.0;
            forecaster.prediction = new double[]{valid, valid, valid};
            ScalingDecision recovered = scaler.decide(new double[]{1}, current, 3, T0);

            assertEquals(scalingUp ? 12 : 20, recovered.targetReplicas(),
                    "an invalid forecast must not delay the next valid scaling decision");
            assertTrue(recovered.changesCapacity());
        }
    }

    @Test
    @DisplayName("finite negative forecasts remain valid and respect the configured minimum")
    void acceptsNegativePredictions() {
        PredictiveScaler scaler = new PredictiveScaler(new FixedForecaster(-100.0), config());

        ScalingDecision decision = scaler.decide(new double[]{1}, 0, 3, T0);

        assertEquals(2, decision.targetReplicas());
        assertEquals(-100.0, decision.predictedPeak());
    }
}
