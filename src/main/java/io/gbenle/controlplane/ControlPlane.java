package io.gbenle.controlplane;

import io.gbenle.controlplane.governance.EvidenceLog;
import io.gbenle.controlplane.governance.GateResult;
import io.gbenle.controlplane.governance.ModelCard;
import io.gbenle.controlplane.governance.PromotionGate;
import io.gbenle.controlplane.observability.AnomalyMonitor;
import io.gbenle.controlplane.observability.DriftDetector;
import io.gbenle.controlplane.observability.DriftReport;
import io.gbenle.controlplane.predictive.PredictiveScaler;
import io.gbenle.controlplane.predictive.ScalingDecision;
import io.gbenle.controlplane.redaction.RedactionPipeline;
import io.gbenle.controlplane.redaction.RedactionResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Wires the four mechanisms into one evaluation cycle.
 *
 * <p>The four are ordinarily built and operated separately, which is the problem this
 * class exists to illustrate. Redaction protects the data, the scaler decides capacity,
 * the gate controls what model is live, and the monitors watch the inputs; each is
 * reasonable alone, and together they interact. A model promoted through the gate
 * changes the cost per record, which changes the capacity the scaler should ask for.
 * Drift in the inputs is often the first visible sign that the deployed model no longer
 * fits, which is a promotion question, not a monitoring question. Holding them in one
 * control plane makes those couplings explicit instead of leaving them to be discovered
 * during an incident.
 */
public final class ControlPlane {

    /**
     * One batch of work arriving at the pipeline.
     *
     * @param records         raw text records to process
     * @param loadHistory     evenly spaced load observations, oldest first
     * @param currentReplicas replicas currently running
     * @param featureSample   numeric feature values observed in this batch
     */
    public record Batch(
            List<String> records,
            double[] loadHistory,
            int currentReplicas,
            double[] featureSample) { }

    /**
     * Everything the control plane concluded in one cycle.
     *
     * @param redacted        the processed records, safe to forward downstream
     * @param entityCounts    how many spans of each type were redacted across the batch
     * @param scaling         the capacity decision
     * @param drift           input distribution comparison against the reference
     * @param anomalies       individual load observations that look wrong
     * @param gate            whether the candidate model may be promoted
     * @param evidenceHead    head of the audit chain after this cycle
     */
    public record CycleReport(
            List<String> redacted,
            Map<String, Integer> entityCounts,
            ScalingDecision scaling,
            DriftReport drift,
            List<AnomalyMonitor.Anomaly> anomalies,
            GateResult gate,
            String evidenceHead) {

        public int totalRedactions() {
            return entityCounts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private final RedactionPipeline redaction;
    private final PredictiveScaler scaler;
    private final PromotionGate gate;
    private final DriftDetector drift;
    private final AnomalyMonitor anomalyMonitor;
    private final EvidenceLog evidence;

    public ControlPlane(RedactionPipeline redaction,
                        PredictiveScaler scaler,
                        PromotionGate gate,
                        DriftDetector drift,
                        AnomalyMonitor anomalyMonitor,
                        EvidenceLog evidence) {
        this.redaction = Objects.requireNonNull(redaction, "redaction");
        this.scaler = Objects.requireNonNull(scaler, "scaler");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.drift = Objects.requireNonNull(drift, "drift");
        this.anomalyMonitor = Objects.requireNonNull(anomalyMonitor, "anomalyMonitor");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    /**
     * Runs one full cycle.
     *
     * @param batch            the work that arrived
     * @param referenceSample  the baseline feature distribution to compare against
     * @param candidateModel   the model version being considered for promotion
     * @param horizon          how many intervals ahead the scaler should protect
     * @param now              evaluation time
     */
    public CycleReport runCycle(Batch batch,
                                double[] referenceSample,
                                ModelCard candidateModel,
                                int horizon,
                                Instant now) {

        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(now, "now");

        List<String> redactedRecords = new ArrayList<>(batch.records().size());
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String record : batch.records()) {
            RedactionResult result = redaction.redact(record);
            redactedRecords.add(result.redactedText());
            result.countsByType().forEach((type, n) -> counts.merge(type, n, Integer::sum));
        }
        evidence.append("BATCH_REDACTED",
                batch.records().size() + " records, " + counts + " spans", now);

        ScalingDecision decision =
                scaler.decide(batch.loadHistory(), batch.currentReplicas(), horizon, now);
        if (decision.changesCapacity()) {
            evidence.append("CAPACITY_CHANGED", decision.toString(), now);
        }

        DriftReport driftReport = drift.compare(referenceSample, batch.featureSample());
        if (driftReport.actionable()) {
            evidence.append("INPUT_DRIFT_DETECTED", driftReport.toString(), now);
        }

        List<AnomalyMonitor.Anomaly> anomalies = anomalyMonitor.scan(batch.loadHistory());

        GateResult gateResult = gate.evaluate(candidateModel, now);

        return new CycleReport(
                List.copyOf(redactedRecords),
                Map.copyOf(counts),
                decision,
                driftReport,
                anomalies,
                gateResult,
                evidence.head());
    }

    public EvidenceLog evidenceLog() {
        return evidence;
    }
}
