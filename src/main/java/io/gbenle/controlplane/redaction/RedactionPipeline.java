package io.gbenle.controlplane.redaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Runs recognizers over text, resolves their disagreements, and applies the policy.
 *
 * <p>Two design choices here are worth stating, because both were learned the hard way.
 *
 * <p>The first is overlap resolution. Independent recognizers routinely claim
 * overlapping spans, and a pipeline that applies all of them produces corrupted output
 * with replacements nested inside replacements. This implementation sorts spans by start
 * offset, then by descending confidence, and walks them greedily, keeping a span only
 * when it begins at or after the end of the last span kept. The loser is not thrown
 * away silently; it is returned in the result so that disagreement between detectors is
 * measurable rather than invisible.
 *
 * <p>The second is that replacement happens in a single left-to-right pass over the
 * original offsets. Replacing spans one at a time in a mutable buffer invalidates every
 * offset after the first replacement whenever the replacement differs in length from the
 * original, which it almost always does. Building the output once, from the original
 * text, removes that whole class of bug.
 *
 * <p>Instances are immutable and safe to share between threads, provided the
 * recognizers supplied are themselves thread-safe.
 */
public final class RedactionPipeline {

    private final List<EntityRecognizer> recognizers;
    private final RedactionPolicy policy;
    private final double minimumConfidence;

    public RedactionPipeline(List<EntityRecognizer> recognizers, RedactionPolicy policy) {
        this(recognizers, policy, 0.0);
    }

    /**
     * @param minimumConfidence spans below this confidence are ignored entirely
     */
    public RedactionPipeline(List<EntityRecognizer> recognizers,
                             RedactionPolicy policy,
                             double minimumConfidence) {
        if (recognizers == null || recognizers.isEmpty()) {
            throw new IllegalArgumentException("at least one recognizer is required");
        }
        if (!(minimumConfidence >= 0.0 && minimumConfidence <= 1.0)) {
            throw new IllegalArgumentException("minimumConfidence must be in [0,1]");
        }
        this.recognizers = List.copyOf(recognizers);
        this.policy = Objects.requireNonNull(policy, "policy");
        this.minimumConfidence = minimumConfidence;
    }

    /** A pipeline with the built-in pattern recognizer and a mask-everything policy. */
    public static RedactionPipeline patternsOnly() {
        return new RedactionPipeline(List.of(new PatternEntityRecognizer()), RedactionPolicy.maskAll());
    }

    /**
     * @throws IllegalArgumentException if a recognizer returns a null span or a span meeting
     *         the confidence threshold that does not match the source text and its offsets
     */
    public RedactionResult redact(String text) {
        if (text == null || text.isEmpty()) {
            return new RedactionResult("", List.of(), Map.of(), List.of());
        }

        List<Entity> candidates = new ArrayList<>();
        for (EntityRecognizer recognizer : recognizers) {
            for (Entity entity : recognizer.recognize(text)) {
                if (entity != null && entity.confidence() < minimumConfidence) {
                    continue;
                }
                validateSpan(entity, text);
                candidates.add(entity);
            }
        }
        if (candidates.isEmpty()) {
            return new RedactionResult(text, List.of(), Map.of(), List.of());
        }

        Collections.sort(candidates);

        List<Entity> applied = new ArrayList<>();
        List<Entity> discarded = new ArrayList<>();
        int lastEnd = 0;
        for (Entity candidate : candidates) {
            if (candidate.start() >= lastEnd) {
                applied.add(candidate);
                lastEnd = candidate.end();
            } else {
                discarded.add(candidate);
            }
        }

        StringBuilder out = new StringBuilder(text.length());
        Map<String, Integer> counts = new LinkedHashMap<>();
        int cursor = 0;
        for (Entity entity : applied) {
            out.append(text, cursor, entity.start());
            out.append(policy.replacementFor(entity));
            counts.merge(entity.type(), 1, Integer::sum);
            cursor = entity.end();
        }
        out.append(text, cursor, text.length());

        return new RedactionResult(out.toString(), applied, counts, discarded);
    }

    private static void validateSpan(Entity entity, String source) {
        if (entity == null || entity.end() > source.length()
                || entity.text() == null || entity.text().length() != entity.length()
                || !source.regionMatches(entity.start(), entity.text(), 0, entity.length())) {
            // A token must be derived from the exact source span, never stale recognizer text.
            throw new IllegalArgumentException("recognizer span must match its source text and offsets");
        }
    }
}
