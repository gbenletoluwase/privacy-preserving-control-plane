package io.gbenle.controlplane.governance;

import java.time.Instant;
import java.util.List;

/**
 * The outcome of evaluating a {@link PromotionGate} against one {@link ModelCard}.
 *
 * @param modelReference the model that was evaluated
 * @param passed         true when every rule passed
 * @param failures       reasons for every rule that did not pass, in rule order
 * @param evaluatedAt    when the evaluation ran
 * @param evidenceHash   hash of the audit record written for this evaluation, empty when none
 */
public record GateResult(
        String modelReference,
        boolean passed,
        List<String> failures,
        Instant evaluatedAt,
        String evidenceHash) {

    public GateResult {
        failures = List.copyOf(failures);
    }

    public String summary() {
        return passed
                ? modelReference + " passed all gate rules"
                : modelReference + " blocked: " + String.join("; ", failures);
    }
}
