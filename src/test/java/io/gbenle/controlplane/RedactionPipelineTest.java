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
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    @DisplayName("rejects stale recognizer text instead of deriving a token for a different value")
    void rejectsMismatchedSpanText() {
        RedactionPolicy policy = RedactionPolicy.builder()
                .defaultStrategy(RedactionPolicy.Strategy.TOKENIZE)
                .tokenKey(KEY)
                .build();
        RedactionPipeline pipeline = new RedactionPipeline(List.of(recognizing(
                new Entity("EMAIL", 0, 15, "bob@example.com", 0.99, "custom"))), policy);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> pipeline.redact("ada@example.com"));

        assertFalse(error.getMessage().contains("ada@example.com"));
        assertFalse(error.getMessage().contains("bob@example.com"));
    }

    @Test
    @DisplayName("rejects recognizer offsets outside the source before replacing any text")
    void rejectsOutOfBoundsSpan() {
        RedactionPipeline pipeline = new RedactionPipeline(List.of(recognizing(
                new Entity("EMAIL", 0, 20, "ada@example.com", 0.99, "custom"))),
                RedactionPolicy.maskAll());

        assertThrows(IllegalArgumentException.class, () -> pipeline.redact("ada@example.com"));
    }

    @Test
    @DisplayName("matches custom recognizer spans using source UTF-16 offsets after supplementary characters")
    void acceptsSourceSpanAfterSupplementaryCharacter() {
        RedactionPipeline pipeline = new RedactionPipeline(List.of(recognizing(
                new Entity("EMAIL", 3, 18, "ada@example.com", 0.99, "custom"))),
                RedactionPolicy.maskAll());

        assertEquals("\uD83D\uDC4B [EMAIL]", pipeline.redact("\uD83D\uDC4B ada@example.com").redactedText());
    }

    @Test
    @DisplayName("accepts adjacent valid spans that reach the source boundaries")
    void acceptsAdjacentBoundarySpans() {
        RedactionPipeline pipeline = new RedactionPipeline(List.of(recognizing(
                new Entity("FIRST", 0, 3, "abc", 0.99, "custom"),
                new Entity("SECOND", 3, 6, "def", 0.99, "custom"))),
                RedactionPolicy.maskAll());

        RedactionResult result = pipeline.redact("abcdef");

        assertEquals("[FIRST][SECOND]", result.redactedText());
        assertEquals(2, result.redactedCount());
        assertTrue(result.discardedSpans().isEmpty());
    }

    @Test
    @DisplayName("spans below the confidence threshold remain ignored before source validation")
    void ignoresLowConfidenceMismatchedSpans() {
        RedactionPipeline pipeline = new RedactionPipeline(List.of(recognizing(
                new Entity("EMAIL", 0, 20, "stale", 0.4, "custom"))),
                RedactionPolicy.maskAll(), 0.8);

        RedactionResult result = pipeline.redact("ada@example.com");

        assertEquals("ada@example.com", result.redactedText());
        assertEquals(0, result.redactedCount());
    }

    private static EntityRecognizer recognizing(Entity... entities) {
        return new EntityRecognizer() {
            @Override
            public List<Entity> recognize(String text) {
                return List.of(entities);
            }

            @Override
            public String name() {
                return "custom";
            }
        };
    }
}
