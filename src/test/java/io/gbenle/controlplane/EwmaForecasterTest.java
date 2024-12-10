package io.gbenle.controlplane;

import io.gbenle.controlplane.predictive.EwmaForecaster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EwmaForecasterTest {

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    @DisplayName("rejects non-finite observations in the initial level and every later update")
    void rejectsNonFiniteHistory(double invalid) {
        EwmaForecaster forecaster = new EwmaForecaster(0.5);
        for (int i = 0; i < 5; i++) {
            double[] history = {10.0, 20.0, 30.0, 40.0, 50.0};
            history[i] = invalid;

            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> forecaster.forecast(history, 3));
            assertEquals("history[" + i + "] must be finite", error.getMessage());
        }
    }

    @Test
    @DisplayName("finite positive, zero, and negative observations preserve the weighted average")
    void acceptsFiniteObservations() {
        EwmaForecaster forecaster = new EwmaForecaster(0.5);

        double[] predicted = forecaster.forecast(new double[]{-8.0, -4.0, 0.0, 4.0, 8.0}, 3);

        assertArrayEquals(new double[]{4.25, 4.25, 4.25}, predicted, 1e-9);
    }
}
