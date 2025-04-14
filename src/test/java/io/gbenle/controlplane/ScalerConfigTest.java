package io.gbenle.controlplane;

import io.gbenle.controlplane.predictive.ScalerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScalerConfigTest {

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0})
    @DisplayName("replica capacity must be finite and strictly positive")
    void rejectsInvalidCapacity(double capacity) {
        assertThrows(IllegalArgumentException.class, () -> ScalerConfig.defaults(capacity, 1, 10));
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, 0.999})
    @DisplayName("the safety factor must be finite and cannot reduce the forecast")
    void rejectsInvalidSafetyFactor(double factor) {
        assertThrows(IllegalArgumentException.class, () -> new ScalerConfig(
                100.0, 1, 10, factor, 0.5, Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    @Test
    @DisplayName("missing scale-up cooldowns fail at configuration creation")
    void rejectsNullScaleUpCooldown() {
        NullPointerException error = assertThrows(NullPointerException.class, () -> new ScalerConfig(
                100.0, 1, 10, 1.2, 0.5, null, Duration.ofSeconds(1)));
        assertEquals("scaleUpCooldown", error.getMessage());
    }

    @Test
    @DisplayName("missing scale-down cooldowns fail at configuration creation")
    void rejectsNullScaleDownCooldown() {
        NullPointerException error = assertThrows(NullPointerException.class, () -> new ScalerConfig(
                100.0, 1, 10, 1.2, 0.5, Duration.ofSeconds(1), null));
        assertEquals("scaleDownCooldown", error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, -1_000_000_000})
    @DisplayName("negative scale-up cooldowns are rejected, including a single negative nanosecond")
    void rejectsNegativeScaleUpCooldown(long nanos) {
        assertThrows(IllegalArgumentException.class, () -> new ScalerConfig(
                100.0, 1, 10, 1.2, 0.5, Duration.ofNanos(nanos), Duration.ofSeconds(1)));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, -1_000_000_000})
    @DisplayName("negative scale-down cooldowns are rejected, including a single negative nanosecond")
    void rejectsNegativeScaleDownCooldown(long nanos) {
        assertThrows(IllegalArgumentException.class, () -> new ScalerConfig(
                100.0, 1, 10, 1.2, 0.5, Duration.ofSeconds(1), Duration.ofNanos(nanos)));
    }

    @Test
    @DisplayName("zero cooldowns remain available to disable waiting in either direction")
    void acceptsZeroCooldowns() {
        ScalerConfig config = new ScalerConfig(100.0, 1, 10, 1.2, 0.5, Duration.ZERO, Duration.ZERO);

        assertEquals(Duration.ZERO, config.scaleUpCooldown());
        assertEquals(Duration.ZERO, config.scaleDownCooldown());
    }

    @Test
    @DisplayName("valid finite boundary values retain their existing configuration behavior")
    void acceptsFiniteBoundaries() {
        assertDoesNotThrow(() -> new ScalerConfig(
                Double.MIN_VALUE, 1, 10, 1.0, 0.5, Duration.ofNanos(1), Duration.ofNanos(1)));
        assertDoesNotThrow(() -> new ScalerConfig(
                Double.MAX_VALUE, 1, 10, Double.MAX_VALUE, 0.5,
                Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }
}
