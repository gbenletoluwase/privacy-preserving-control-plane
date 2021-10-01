package io.gbenle.controlplane;

import io.gbenle.controlplane.redaction.Entity;
import io.gbenle.controlplane.redaction.EntityRecognizer;
import io.gbenle.controlplane.redaction.PatternEntityRecognizer;
import io.gbenle.controlplane.redaction.RedactionPipeline;
import io.gbenle.controlplane.redaction.RedactionPolicy;
import io.gbenle.controlplane.redaction.RedactionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactionPipelineTest {

    private static final byte[] KEY = "a-test-key-of-sufficient-length".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("masks the structured identifiers it recognizes")
    void masksKnownIdentifiers() {
        RedactionResult result = RedactionPipeline.patternsOnly()
                .redact("write to ada@example.com or call 404-555-0132");

        assertEquals("write to [EMAIL] or call [PHONE]", result.redactedText());
        assertEquals(2, result.redactedCount());
        assertFalse(result.clean());
    }

    @Test
    @DisplayName("applies the Luhn checksum so order numbers are not mistaken for cards")
    void luhnRejectsNonCardDigitStrings() {
        RedactionResult result = RedactionPipeline.patternsOnly()
                .redact("card 4111111111111111 reference 4111111111111112");

        assertEquals("card [CREDIT_CARD] reference 4111111111111112", result.redactedText());
        assertTrue(PatternEntityRecognizer.passesLuhn("4111 1111 1111 1111"));
        assertFalse(PatternEntityRecognizer.passesLuhn("4111111111111112"));
    }

    @Test
    @DisplayName("keeps offsets correct when replacements differ in length from the original")
    void rebuildsTextFromOriginalOffsets() {
        RedactionResult result = RedactionPipeline.patternsOnly()
                .redact("a@b.co then 111-22-3333 then c@d.co end");

        assertEquals("[EMAIL] then [SSN] then [EMAIL] end", result.redactedText());
        assertEquals(2, result.countsByType().get("EMAIL"));
        assertEquals(1, result.countsByType().get("SSN"));
    }

    @Test
    @DisplayName("tokenization is stable for one value and distinct across values")
    void tokenizationIsStableAndDistinct() {
        RedactionPolicy policy = RedactionPolicy.builder()
                .defaultStrategy(RedactionPolicy.Strategy.TOKENIZE)
                .tokenKey(KEY)
                .build();
        RedactionPipeline pipeline =
                new RedactionPipeline(List.of(new PatternEntityRecognizer()), policy);

        String first = pipeline.redact("ada@example.com").redactedText();
        String again = pipeline.redact("ada@example.com").redactedText();
        String other = pipeline.redact("grace@example.com").redactedText();

        assertEquals(first, again, "the same address must always yield the same token");
        assertNotEquals(first, other, "different addresses must yield different tokens");
        assertTrue(first.startsWith("[EMAIL_"));
    }

    @Test
    @DisplayName("resolves overlapping spans in favour of the higher-confidence detector")
    void resolvesOverlapsByConfidence() {
        EntityRecognizer weak = new EntityRecognizer() {
            @Override
            public List<Entity> recognize(String text) {
                return List.of(new Entity("GUESS", 0, 9, text.substring(0, 9), 0.40, name()));
            }

            @Override
            public String name() {
                return "weak";
            }
        };
        EntityRecognizer strong = new EntityRecognizer() {
            @Override
            public List<Entity> recognize(String text) {
                return List.of(new Entity("CERTAIN", 0, 5, text.substring(0, 5), 0.99, name()));
            }

            @Override
            public String name() {
                return "strong";
            }
        };

        RedactionResult result =
                new RedactionPipeline(List.of(weak, strong), RedactionPolicy.maskAll())
                        .redact("abcdefghijklmno");

        assertEquals(1, result.appliedSpans().size());
        assertEquals("CERTAIN", result.appliedSpans().get(0).type());
        assertEquals(1, result.discardedSpans().size(),
                "the losing span is reported, not silently dropped");
        assertEquals("[CERTAIN]fghijklmno", result.redactedText());
    }

    @Test
    @DisplayName("leaves text without identifiers untouched")
    void passesCleanTextThrough() {
        RedactionResult result = RedactionPipeline.patternsOnly()
                .redact("the batch completed in 412 milliseconds");

        assertTrue(result.clean());
        assertEquals("the batch completed in 412 milliseconds", result.redactedText());
    }

    @Test
    @DisplayName("a policy that tokenizes without a key is rejected at construction")
    void tokenizeRequiresKey() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> RedactionPolicy.builder()
                        .defaultStrategy(RedactionPolicy.Strategy.TOKENIZE)
                        .build());
    }
}
