package io.gbenle.controlplane.governance;

import java.time.Instant;
import java.util.Optional;

/**
 * One condition a model must satisfy before it may be promoted.
 *
 * <p>A rule returns the reason it failed, or an empty optional when it passed.
 * Returning the reason rather than a boolean is what lets the gate tell an engineer
 * what to fix instead of only that something is wrong.
 */
@FunctionalInterface
public interface GateRule {

    /**
     * @param card the model under evaluation
     * @param now  evaluation time, supplied so rules about freshness are testable
     * @return the failure reason, or empty when the rule passes
     */
    Optional<String> check(ModelCard card, Instant now);
}
