package io.gbenle.controlplane;

import io.gbenle.controlplane.predictive.EwmaForecaster;
import io.gbenle.controlplane.predictive.Forecaster;
import io.gbenle.controlplane.predictive.HoltWintersForecaster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoltWintersForecasterTest {

    private static final int SEASON = 12;

    /** A clean series with a known level, linear trend, and sinusoidal season. */
    private static double[] syntheticSeries(int length) {
        double[] series = new double[length];
        for (int t = 0; t < length; t++) {
            series[t] = 100.0 + 2.0 * t + 30.0 * Math.sin(2 * Math.PI * t / SEASON);
        }
        return series;
    }

    @Test
    @DisplayName("recovers trend and seasonality well enough to forecast a full season ahead")
    void forecastsSeasonalSeries() {
        double[] full = syntheticSeries(84);
        double[] history = Arrays.copyOf(full, 72);

        double[] predicted = new HoltWintersForecaster(0.3, 0.1, 0.3, SEASON).forecast(history, 12);

        double totalError = 0.0;
        for (int h = 0; h < 12; h++) {
            totalError += Math.abs(predicted[h] - full[72 + h]) / Math.abs(full[72 + h]);
        }
        double meanAbsolutePercentageError = totalError / 12;

        assertTrue(meanAbsolutePercentageError < 0.05,
                "expected MAPE under 5 percent, got " + meanAbsolutePercentageError);
    }

    @Test
    @DisplayName("beats a flat EWMA baseline on a trending seasonal series")
    void beatsFlatBaseline() {
        double[] full = syntheticSeries(84);
        double[] history = Arrays.copyOf(full, 72);

        double seasonalError = meanAbsoluteError(
                new HoltWintersForecaster(0.3, 0.1, 0.3, SEASON), history, full);
        double flatError = meanAbsoluteError(new EwmaForecaster(0.3), history, full);

        assertTrue(seasonalError < flatError,
                "seasonal model error " + seasonalError + " should beat flat " + flatError);
    }

    private static double meanAbsoluteError(Forecaster forecaster, double[] history, double[] truth) {
        double[] predicted = forecaster.forecast(history, 12);
        double sum = 0.0;
        for (int h = 0; h < 12; h++) {
            sum += Math.abs(predicted[h] - truth[history.length + h]);
        }
        return sum / 12;
    }

    @Test
    @DisplayName("refuses a history shorter than two full seasons")
    void rejectsShortHistory() {
        HoltWintersForecaster forecaster = new HoltWintersForecaster(0.3, 0.1, 0.3, SEASON);
        assertEquals(24, forecaster.minimumHistory());
        assertThrows(IllegalArgumentException.class,
                () -> forecaster.forecast(syntheticSeries(23), 4));
    }

    @Test
    @DisplayName("refuses smoothing parameters outside the open unit interval")
    void rejectsBadParameters() {
        assertThrows(IllegalArgumentException.class,
                () -> new HoltWintersForecaster(0.0, 0.1, 0.3, SEASON));
        assertThrows(IllegalArgumentException.class,
                () -> new HoltWintersForecaster(0.3, 1.0, 0.3, SEASON));
        assertThrows(IllegalArgumentException.class,
                () -> new HoltWintersForecaster(0.3, 0.1, 0.3, 1));
    }
}
