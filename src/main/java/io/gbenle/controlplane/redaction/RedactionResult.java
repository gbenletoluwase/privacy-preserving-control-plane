package io.gbenle.controlplane.redaction;

import java.util.List;
import java.util.Map;

/**
 * The output of one pass of the {@link RedactionPipeline}.
 *
 * @param redactedText   the text with replacements applied
 * @param appliedSpans   the non-overlapping spans that were replaced, in document order
 * @param countsByType   how many spans of each type were replaced
 * @param discardedSpans spans that lost overlap resolution to a higher-confidence span
 */
public record RedactionResult(
        String redactedText,
        List<Entity> appliedSpans,
        Map<String, Integer> countsByType,
        List<Entity> discardedSpans) {

    public RedactionResult {
        appliedSpans = List.copyOf(appliedSpans);
        countsByType = Map.copyOf(countsByType);
        discardedSpans = List.copyOf(discardedSpans);
    }

    /** Total number of spans replaced. */
    public int redactedCount() {
        return appliedSpans.size();
    }

    /** True when nothing sensitive was found. */
    public boolean clean() {
        return appliedSpans.isEmpty();
    }
}
