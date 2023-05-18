package io.gbenle.controlplane;

import io.gbenle.controlplane.governance.EvidenceLog;
import io.gbenle.controlplane.governance.ModelCard;
import io.gbenle.controlplane.governance.PromotionGate;
import io.gbenle.controlplane.observability.AnomalyMonitor;
import io.gbenle.controlplane.observability.DriftDetector;
import io.gbenle.controlplane.predictive.HoltWintersForecaster;
import io.gbenle.controlplane.predictive.PredictiveScaler;
import io.gbenle.controlplane.predictive.ScalerConfig;
import io.gbenle.controlplane.redaction.PatternEntityRecognizer;
import io.gbenle.controlplane.redaction.RedactionPipeline;
import io.gbenle.controlplane.redaction.RedactionPolicy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * A runnable end-to-end example. Run with {@code mvn compile exec:java}.
 */
public final class ControlPlaneDemo {

    private ControlPlaneDemo() { }

    public static void main(String[] args) {
        Instant now = Instant.parse("2026-03-14T09:00:00Z");

        RedactionPolicy policy = RedactionPolicy.builder()
                .defaultStrategy(RedactionPolicy.Strategy.MASK)
                .with("EMAIL", RedactionPolicy.Strategy.TOKENIZE)
                .with("PHONE", RedactionPolicy.Strategy.TOKENIZE)
                .with("IPV4", RedactionPolicy.Strategy.KEEP)
                .tokenKey("demo-key-not-for-production".getBytes(StandardCharsets.UTF_8))
                .build();

        RedactionPipeline redaction =
                new RedactionPipeline(List.of(new PatternEntityRecognizer()), policy);

        PredictiveScaler scaler = new PredictiveScaler(
                HoltWintersForecaster.hourlyWithDailySeason(),
                ScalerConfig.defaults(500.0, 2, 40));

        EvidenceLog evidence = new EvidenceLog();

        PromotionGate gate = PromotionGate.builder()
                .recordingTo(evidence)
                .requireMetricAtLeast("f1", 0.90)
                .requireMetricAtMost("falsePositiveRate", 0.02)
                .requireFreshness(Duration.ofDays(90))
                .requireDatasetProvenance()
                .requireOwner()
                .build();

        ControlPlane plane = new ControlPlane(
                redaction, scaler, gate,
                DriftDetector.deciles(), AnomalyMonitor.standard(), evidence);

        ControlPlane.Batch batch = new ControlPlane.Batch(
                List.of(
                        "Caller ada.lovelace@example.com rang from 404-555-0132 about order 88213.",
                        "Card 4111 1111 1111 1111 declined; retry from host 10.2.14.9 succeeded.",
                        "Agent note: SSN 123-45-6789 supplied for identity check, do not retain."),
                dailyLoadCurve(72, 1234L),
                6,
                sample(400, 50.0, 8.0, 99L));

        ModelCard candidate = new ModelCard(
                "redactor-ner",
                "3.2.0",
                now.minus(Duration.ofDays(12)),
                Map.of("f1", 0.934, "precision", 0.951, "recall", 0.918, "falsePositiveRate", 0.013),
                "sha256:9f2b7c41e8d05a63",
                "data-platform");

        ControlPlane.CycleReport report =
                plane.runCycle(batch, sample(2000, 50.0, 8.0, 7L), candidate, 6, now);

        System.out.println("=== redacted records ===");
        report.redacted().forEach(r -> System.out.println("  " + r));
        System.out.println();
        System.out.println("spans matched  : " + report.totalRedactions() + " "
                + new TreeMap<>(report.entityCounts()));
        System.out.println("scaling        : " + report.scaling().rationale());
        System.out.println("                 " + report.scaling().currentReplicas()
                + " -> " + report.scaling().targetReplicas() + " replicas");
        System.out.println("input drift    : " + report.drift());
        System.out.println("load anomalies : " + report.anomalies().size());
        System.out.println("promotion gate : " + report.gate().summary());
        System.out.println("audit chain    : " + evidence.size()
                + " records, intact=" + (evidence.verify() == -1L));
    }

    /** A synthetic three-day hourly load curve with a daytime peak and mild noise. */
    private static double[] dailyLoadCurve(int hours, long seed) {
        Random random = new Random(seed);
        double[] series = new double[hours];
        for (int t = 0; t < hours; t++) {
            int hourOfDay = t % 24;
            double seasonal = 900.0 * Math.exp(-Math.pow(hourOfDay - 14, 2) / 18.0);
            series[t] = 600.0 + seasonal + 4.0 * t + random.nextGaussian() * 25.0;
        }
        return series;
    }

    private static double[] sample(int n, double mean, double sd, long seed) {
        Random random = new Random(seed);
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = mean + random.nextGaussian() * sd;
        }
        return out;
    }
}
