package io.gbenle.controlplane.governance;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A qualification gate that a model must pass before it reaches production traffic.
 *
 * <p>The argument for putting this in the deployment path rather than in a checklist is
 * that a checklist is skipped under deadline pressure and a gate is not. The gate is
 * ordinary code, it runs where the deployment runs, and when it refuses it says exactly
 * which threshold was missed.
 *
 * <p>Every evaluation is written to an {@link EvidenceLog} when one is attached, so the
 * question asked after an incident, which model was promoted, on what evidence, and when,
 * has an answer that does not depend on anyone having remembered to write it down.
 *
 * <p>Instances are immutable once built and safe to share between threads.
 */
public final class PromotionGate {

    private final List<GateRule> rules;
    private final EvidenceLog evidenceLog;

    private PromotionGate(List<GateRule> rules, EvidenceLog evidenceLog) {
        this.rules = List.copyOf(rules);
        this.evidenceLog = evidenceLog;
    }

    public static Builder builder() {
        return new Builder();
    }

    public GateResult evaluate(ModelCard card, Instant now) {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(now, "now");

        List<String> failures = new ArrayList<>();
        for (GateRule rule : rules) {
            rule.check(card, now).ifPresent(failures::add);
        }
        boolean passed = failures.isEmpty();

        String evidenceHash = "";
        if (evidenceLog != null) {
            String detail = passed
                    ? card.reference() + " passed " + rules.size() + " rules"
                    : card.reference() + " blocked: " + String.join("; ", failures);
            evidenceHash = evidenceLog.append("MODEL_PROMOTION_EVALUATED", detail, now).hash();
        }
        return new GateResult(card.reference(), passed, failures, now, evidenceHash);
    }

    public int ruleCount() {
        return rules.size();
    }

    public static final class Builder {
        private final List<GateRule> rules = new ArrayList<>();
        private EvidenceLog evidenceLog;

        /** Attaches an audit log; every evaluation appends one record to it. */
        public Builder recordingTo(EvidenceLog log) {
            this.evidenceLog = log;
            return this;
        }

        /** Fails when the named metric is absent, non-finite, or below {@code threshold}. */
        public Builder requireMetricAtLeast(String metric, double threshold) {
            if (!Double.isFinite(threshold)) {
                throw new IllegalArgumentException("threshold must be finite");
            }
            rules.add((card, now) -> {
                Optional<Double> value = card.metric(metric);
                if (value.isEmpty()) {
                    return Optional.of("metric '" + metric + "' is not reported");
                }
                if (!Double.isFinite(value.get())) {
                    return Optional.of("metric '" + metric + "' is not finite");
                }
                if (value.get() < threshold) {
                    return Optional.of("%s is %.4f, below the required %.4f"
                            .formatted(metric, value.get(), threshold));
                }
                return Optional.empty();
            });
            return this;
        }

        /** Fails when the named metric is absent, non-finite, or above {@code ceiling}. */
        public Builder requireMetricAtMost(String metric, double ceiling) {
            if (!Double.isFinite(ceiling)) {
                throw new IllegalArgumentException("ceiling must be finite");
            }
            rules.add((card, now) -> {
                Optional<Double> value = card.metric(metric);
                if (value.isEmpty()) {
                    return Optional.of("metric '" + metric + "' is not reported");
                }
                if (!Double.isFinite(value.get())) {
                    return Optional.of("metric '" + metric + "' is not finite");
                }
                if (value.get() > ceiling) {
                    return Optional.of("%s is %.4f, above the permitted %.4f"
                            .formatted(metric, value.get(), ceiling));
                }
                return Optional.empty();
            });
            return this;
        }

        /** Fails when the model was trained longer than {@code maxAge} before evaluation. */
        public Builder requireFreshness(Duration maxAge) {
            Objects.requireNonNull(maxAge, "maxAge");
            rules.add((card, now) -> {
                Duration age = Duration.between(card.trainedAt(), now);
                if (age.compareTo(maxAge) > 0) {
                    return Optional.of("model is " + age.toDays()
                            + " days old, limit is " + maxAge.toDays() + " days");
                }
                return Optional.empty();
            });
            return this;
        }

        /** Fails when the card does not record which dataset produced the model. */
        public Builder requireDatasetProvenance() {
            rules.add((card, now) -> card.datasetHash().isBlank()
                    ? Optional.of("no training dataset hash recorded")
                    : Optional.empty());
            return this;
        }

        /** Fails when no accountable owner is named. */
        public Builder requireOwner() {
            rules.add((card, now) -> card.owner().isBlank()
                    ? Optional.of("no accountable owner recorded")
                    : Optional.empty());
            return this;
        }

        /** Adds an arbitrary rule. */
        public Builder custom(GateRule rule) {
            rules.add(Objects.requireNonNull(rule, "rule"));
            return this;
        }

        public PromotionGate build() {
            if (rules.isEmpty()) {
                throw new IllegalStateException("a gate with no rules would approve anything");
            }
            return new PromotionGate(rules, evidenceLog);
        }
    }
}
