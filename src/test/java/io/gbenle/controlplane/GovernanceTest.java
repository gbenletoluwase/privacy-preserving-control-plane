package io.gbenle.controlplane;

import io.gbenle.controlplane.governance.EvidenceLog;
import io.gbenle.controlplane.governance.GateResult;
import io.gbenle.controlplane.governance.ModelCard;
import io.gbenle.controlplane.governance.PromotionGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    private static ModelCard card(double f1, Instant trainedAt, String datasetHash, String owner) {
        return new ModelCard("redactor-ner", "1.0.0", trainedAt,
                Map.of("f1", f1, "falsePositiveRate", 0.01), datasetHash, owner);
    }

    private static PromotionGate.Builder standardGate() {
        return PromotionGate.builder()
                .requireMetricAtLeast("f1", 0.90)
                .requireMetricAtMost("falsePositiveRate", 0.02)
                .requireFreshness(Duration.ofDays(90))
                .requireDatasetProvenance()
                .requireOwner();
    }

    @Test
    @DisplayName("passes a model that satisfies every rule")
    void passesQualifiedModel() {
        GateResult result = standardGate().build()
                .evaluate(card(0.94, NOW.minus(Duration.ofDays(10)), "sha256:abc", "data-platform"), NOW);

        assertTrue(result.passed());
        assertTrue(result.failures().isEmpty());
    }

    @Test
    @DisplayName("blocks a model below the quality threshold and says by how much")
    void blocksUnderperformingModel() {
        GateResult result = standardGate().build()
                .evaluate(card(0.71, NOW.minus(Duration.ofDays(10)), "sha256:abc", "data-platform"), NOW);

        assertFalse(result.passed());
        assertEquals(1, result.failures().size());
        assertTrue(result.failures().get(0).contains("f1"));
        assertTrue(result.failures().get(0).contains("0.9000"));
    }

    @Test
    @DisplayName("non-finite reported metrics cannot pass either kind of threshold")
    void blocksNonFiniteMetrics() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            ModelCard model = card(value, NOW, "sha256:abc", "team");
            for (PromotionGate gate : new PromotionGate[]{
                    PromotionGate.builder().requireMetricAtLeast("f1", 0.90).build(),
                    PromotionGate.builder().requireMetricAtMost("f1", 0.95).build()}) {
                GateResult result = gate.evaluate(model, NOW);
                assertFalse(result.passed(), "non-finite metric must be blocked: " + value);
                assertEquals(1, result.failures().size());
                assertTrue(result.failures().get(0).contains("f1"));
                assertTrue(result.failures().get(0).contains("not finite"));
            }
        }
    }

    @Test
    @DisplayName("non-finite metric thresholds are rejected when configuring the gate")
    void rejectsNonFiniteThresholds() {
        for (double threshold : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> PromotionGate.builder().requireMetricAtLeast("f1", threshold));
            assertThrows(IllegalArgumentException.class,
                    () -> PromotionGate.builder().requireMetricAtMost("f1", threshold));
        }
    }

    @Test
    @DisplayName("finite metrics exactly at their thresholds remain valid")
    void acceptsInclusiveThresholds() {
        PromotionGate gate = PromotionGate.builder()
                .requireMetricAtLeast("f1", 0.90)
                .requireMetricAtMost("f1", 0.90)
                .build();

        assertTrue(gate.evaluate(card(0.90, NOW, "sha256:abc", "team"), NOW).passed());
    }

    @Test
    @DisplayName("reports every failing rule, not only the first")
    void reportsAllFailures() {
        GateResult result = standardGate().build()
                .evaluate(card(0.40, NOW.minus(Duration.ofDays(400)), "", ""), NOW);

        assertFalse(result.passed());
        assertEquals(4, result.failures().size(),
                "low f1, stale, no provenance, no owner");
    }

    @Test
    @DisplayName("freshness rules reject negative maximum ages at construction")
    void rejectsNegativeMaximumAge() {
        assertThrows(IllegalArgumentException.class,
                () -> PromotionGate.builder().requireFreshness(Duration.ofNanos(-1)));
    }

    @Test
    @DisplayName("a future training timestamp blocks promotion and is recorded in the audit log")
    void blocksFutureTrainingTimestamp() {
        EvidenceLog log = new EvidenceLog();
        PromotionGate gate = PromotionGate.builder()
                .requireFreshness(Duration.ofDays(90))
                .recordingTo(log)
                .build();

        GateResult result = gate.evaluate(card(0.94, NOW.plusNanos(1), "sha256:abc", "team"), NOW);

        assertFalse(result.passed());
        assertEquals(1, result.failures().size());
        assertTrue(result.failures().get(0).contains("future"));
        assertEquals(1, log.size());
        assertTrue(log.records().get(0).detail().contains("blocked"));
        assertEquals(-1L, log.verify());
    }

    @Test
    @DisplayName("freshness includes the maximum age and excludes even one nanosecond beyond it")
    void respectsFreshnessBoundary() {
        Duration limit = Duration.ofDays(90);
        PromotionGate gate = PromotionGate.builder().requireFreshness(limit).build();
        Instant boundary = NOW.minus(limit);

        assertTrue(gate.evaluate(card(0.94, boundary, "sha256:abc", "team"), NOW).passed());
        assertFalse(gate.evaluate(card(0.94, boundary.minusNanos(1), "sha256:abc", "team"), NOW).passed());
    }

    @Test
    @DisplayName("zero maximum age accepts only a model trained at the evaluation instant")
    void zeroMaximumAgeRequiresExactTimestamp() {
        PromotionGate gate = PromotionGate.builder().requireFreshness(Duration.ZERO).build();

        assertTrue(gate.evaluate(card(0.94, NOW, "sha256:abc", "team"), NOW).passed());
        assertFalse(gate.evaluate(card(0.94, NOW.minusNanos(1), "sha256:abc", "team"), NOW).passed());
        assertFalse(gate.evaluate(card(0.94, NOW.plusNanos(1), "sha256:abc", "team"), NOW).passed());
    }

    @Test
    @DisplayName("a gate with no rules is a programming error")
    void emptyGateIsRejected() {
        assertThrows(IllegalStateException.class, () -> PromotionGate.builder().build());
    }

    @Test
    @DisplayName("every evaluation leaves an audit record")
    void evaluationsAreRecorded() {
        EvidenceLog log = new EvidenceLog();
        PromotionGate gate = standardGate().recordingTo(log).build();

        gate.evaluate(card(0.94, NOW.minus(Duration.ofDays(1)), "sha256:abc", "team"), NOW);
        gate.evaluate(card(0.10, NOW.minus(Duration.ofDays(1)), "sha256:abc", "team"), NOW);

        assertEquals(2, log.size());
        assertEquals(-1L, log.verify());
        assertTrue(log.records().get(1).detail().contains("blocked"));
    }

    @Test
    @DisplayName("the audit chain detects a record altered after the fact")
    void chainDetectsTampering() {
        EvidenceLog log = new EvidenceLog();
        log.append("ONE", "first", NOW);
        log.append("TWO", "second", NOW.plusSeconds(1));
        log.append("THREE", "third", NOW.plusSeconds(2));

        assertEquals(-1L, log.verify(), "an untouched chain verifies");

        EvidenceLog forged = new EvidenceLog();
        forged.append("ONE", "first", NOW);
        forged.append("TWO", "edited", NOW.plusSeconds(1));
        assertNotEquals(log.records().get(1).hash(), forged.records().get(1).hash(),
                "changing the detail changes the record hash, breaking the next link");
    }

    @Test
    @DisplayName("the head hash advances with every append")
    void headAdvances() {
        EvidenceLog log = new EvidenceLog();
        assertEquals(EvidenceLog.GENESIS, log.head());
        String first = log.append("ONE", "a", NOW).hash();
        assertEquals(first, log.head());
        String second = log.append("TWO", "b", NOW.plusSeconds(1)).hash();
        assertNotEquals(first, second);
        assertEquals(second, log.head());
    }
}
