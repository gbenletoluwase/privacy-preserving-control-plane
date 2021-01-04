package io.gbenle.controlplane;

import io.gbenle.controlplane.observability.AnomalyMonitor;
import io.gbenle.controlplane.observability.DriftDetector;
import io.gbenle.controlplane.observability.DriftReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservabilityTest {

    private static double[] gaussian(int n, double mean, double sd, long seed) {
        Random random = new Random(seed);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = mean + random.nextGaussian() * sd;
        }
        return out;
    }

    @Test
    @DisplayName("reports stability when the distribution has not moved")
    void stableWhenUnchanged() {
        DriftReport report = DriftDetector.deciles()
                .compare(gaussian(2000, 0, 1, 1L), gaussian(1000, 0, 1, 2L));

        assertEquals(DriftReport.Severity.STABLE, report.severity());
        assertFalse(report.actionable());
        assertTrue(report.psi() < 0.10, "got PSI " + report.psi());
    }

    @Test
    @DisplayName("reports a significant shift when the mean moves by one and a half sigma")
    void detectsShift() {
        DriftReport report = DriftDetector.deciles()
                .compare(gaussian(2000, 0, 1, 1L), gaussian(1000, 1.5, 1, 3L));

        assertEquals(DriftReport.Severity.SIGNIFICANT, report.severity());
        assertTrue(report.actionable());
        assertTrue(report.psi() > 0.25, "got PSI " + report.psi());
    }

    @Test
    @DisplayName("PSI bands match the conventional thresholds")
    void classifiesBands() {
        assertEquals(DriftReport.Severity.STABLE, DriftReport.classify(0.099));
        assertEquals(DriftReport.Severity.MODERATE, DriftReport.classify(0.10));
        assertEquals(DriftReport.Severity.MODERATE, DriftReport.classify(0.249));
        assertEquals(DriftReport.Severity.SIGNIFICANT, DriftReport.classify(0.25));
    }

    @Test
    @DisplayName("finds a single injected spike")
    void findsSpike() {
        double[] series = new double[40];
        for (int i = 0; i < series.length; i++) {
            series[i] = 10.0 + (i % 3);
        }
        series[17] = 95.0;

        List<AnomalyMonitor.Anomaly> found = AnomalyMonitor.standard().scan(series);

        assertEquals(1, found.size());
        assertEquals(17, found.get(0).index());
        assertEquals(95.0, found.get(0).value());
    }

    @Test
    @DisplayName("several extreme points do not mask one another, as they would with a mean")
    void robustToMultipleOutliers() {
        double[] series = new double[40];
        for (int i = 0; i < series.length; i++) {
            series[i] = 10.0 + (i % 3);
        }
        series[5] = 400.0;
        series[6] = 420.0;
        series[7] = 390.0;

        List<AnomalyMonitor.Anomaly> found = AnomalyMonitor.standard().scan(series);

        assertEquals(3, found.size(), "all three should be reported");
    }

    @Test
    @DisplayName("a constant series has no anomalies and does not divide by zero")
    void constantSeriesIsQuiet() {
        double[] flat = new double[20];
        java.util.Arrays.fill(flat, 7.0);
        assertTrue(AnomalyMonitor.standard().scan(flat).isEmpty());
    }
}
