package io.gbenle.controlplane;

import io.gbenle.controlplane.governance.EvidenceLog;
import io.gbenle.controlplane.governance.GateResult;
import io.gbenle.controlplane.observability.AnomalyMonitor;
import io.gbenle.controlplane.observability.DriftReport;
import io.gbenle.controlplane.predictive.ScalingDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlPlaneTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    @Test
    @DisplayName("a batch retains its original records and samples when caller inputs change")
    void snapshotsBatchInputs() {
        List<String> records = new ArrayList<>(List.of("ada@example.com"));
        double[] load = {10.0, 20.0, 30.0};
        double[] features = {1.0, 2.0, 3.0};
        ControlPlane.Batch batch = new ControlPlane.Batch(records, load, 2, features);

        records.set(0, "changed@example.com");
        records.add("another record");
        load[0] = 999.0;
        features[0] = 999.0;

        assertEquals(List.of("ada@example.com"), batch.records());
        assertArrayEquals(new double[]{10.0, 20.0, 30.0}, batch.loadHistory());
        assertArrayEquals(new double[]{1.0, 2.0, 3.0}, batch.featureSample());
        assertEquals(2, batch.currentReplicas());
    }

    @Test
    @DisplayName("batch accessors cannot be used to mutate stored records or samples")
    void protectsBatchAccessors() {
        ControlPlane.Batch batch = new ControlPlane.Batch(
                List.of("record"), new double[]{10.0, 20.0}, 2, new double[]{1.0, 2.0});

        batch.loadHistory()[0] = 999.0;
        batch.featureSample()[0] = 999.0;

        assertThrows(UnsupportedOperationException.class, () -> batch.records().set(0, "changed"));
        assertArrayEquals(new double[]{10.0, 20.0}, batch.loadHistory());
        assertArrayEquals(new double[]{1.0, 2.0}, batch.featureSample());
    }

    @Test
    @DisplayName("snapshotting preserves nullable text records accepted by the redaction pipeline")
    void preservesNullRecords() {
        List<String> records = new ArrayList<>();
        records.add(null);
        ControlPlane.Batch batch = new ControlPlane.Batch(records, new double[]{1.0}, 1, new double[]{1.0});

        records.set(0, "changed");

        assertNull(batch.records().get(0));
        assertThrows(UnsupportedOperationException.class, () -> batch.records().clear());
    }

    @Test
    @DisplayName("null batch containers fail at construction instead of during a cycle")
    void rejectsNullBatchContainers() {
        assertThrows(NullPointerException.class,
                () -> new ControlPlane.Batch(null, new double[0], 1, new double[0]));
        assertThrows(NullPointerException.class,
                () -> new ControlPlane.Batch(List.of(), null, 1, new double[0]));
        assertThrows(NullPointerException.class,
                () -> new ControlPlane.Batch(List.of(), new double[0], 1, null));
    }

    @Test
    @DisplayName("directly constructed reports retain their results when input collections change")
    void snapshotsReportCollections() {
        List<String> redacted = new ArrayList<>(List.of("[EMAIL]"));
        Map<String, Integer> counts = new HashMap<>(Map.of("EMAIL", 1));
        AnomalyMonitor.Anomaly anomaly = new AnomalyMonitor.Anomaly(2, 99.0, 4.0);
        List<AnomalyMonitor.Anomaly> anomalies = new ArrayList<>(List.of(anomaly));
        ControlPlane.CycleReport report = report(redacted, counts, anomalies);

        redacted.clear();
        counts.put("EMAIL", 999);
        counts.put("PHONE", 1);
        anomalies.clear();

        assertEquals(List.of("[EMAIL]"), report.redacted());
        assertEquals(Map.of("EMAIL", 1), report.entityCounts());
        assertEquals(1, report.totalRedactions());
        assertEquals(List.of(anomaly), report.anomalies());
    }

    @Test
    @DisplayName("report collection accessors cannot modify the recorded results")
    void protectsReportAccessors() {
        AnomalyMonitor.Anomaly anomaly = new AnomalyMonitor.Anomaly(2, 99.0, 4.0);
        ControlPlane.CycleReport report = report(
                new ArrayList<>(List.of("[EMAIL]")),
                new HashMap<>(Map.of("EMAIL", 1)),
                new ArrayList<>(List.of(anomaly)));

        assertThrows(UnsupportedOperationException.class, () -> report.redacted().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.entityCounts().put("EMAIL", 999));
        assertThrows(UnsupportedOperationException.class, () -> report.anomalies().clear());
        assertEquals(1, report.totalRedactions());
    }

    private static ControlPlane.CycleReport report(List<String> redacted,
                                                   Map<String, Integer> counts,
                                                   List<AnomalyMonitor.Anomaly> anomalies) {
        return new ControlPlane.CycleReport(redacted, counts,
                new ScalingDecision(NOW, 2, 2, 30.0, "test", "capacity is sufficient"),
                new DriftReport(0.0, DriftReport.Severity.STABLE, 2, 3), anomalies,
                new GateResult("model:1", true, List.of(), NOW, ""), EvidenceLog.GENESIS);
    }
}
