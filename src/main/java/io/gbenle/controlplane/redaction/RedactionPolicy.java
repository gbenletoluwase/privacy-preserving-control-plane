package io.gbenle.controlplane.redaction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Decides what replaces each kind of sensitive span.
 *
 * <p>Redaction is usually discussed as though deletion were the only option, but
 * deletion destroys analytic value that the business legitimately needs. A support
 * organization that cannot tell whether two complaints came from the same customer has
 * lost the ability to measure repeat contact rate. Pseudonymization answers both
 * concerns at once: the same email address yields the same opaque token everywhere, so
 * records still join, while the token reveals nothing about the address without the key.
 *
 * <p>Tokens are derived with HMAC-SHA256 rather than a bare hash, because a bare hash of
 * a low-entropy value such as a phone number can be reversed by enumeration in seconds.
 * The key must be stored in a secrets manager and rotated on the same schedule as any
 * other production key; rotating it breaks joins across the rotation boundary, which is
 * the intended trade.
 */
public final class RedactionPolicy {

    public enum Strategy {
        /** Replace with a bare type label, for example {@code [EMAIL]}. */
        MASK,
        /** Replace with a stable keyed pseudonym, for example {@code [EMAIL_3f9a2c]}. */
        TOKENIZE,
        /** Replace with a truncated unkeyed digest. Use only for high-entropy values. */
        HASH,
        /** Remove entirely, leaving nothing. */
        DROP,
        /** Leave the original text in place. */
        KEEP
    }

    private final Map<String, Strategy> byType;
    private final Strategy defaultStrategy;
    private final byte[] tokenKey;

    private RedactionPolicy(Map<String, Strategy> byType, Strategy defaultStrategy, byte[] tokenKey) {
        this.byType = Map.copyOf(byType);
        this.defaultStrategy = defaultStrategy;
        this.tokenKey = tokenKey == null ? null : tokenKey.clone();
        if (this.tokenKey == null && requiresKey()) {
            throw new IllegalArgumentException(
                    "a token key is required when any type uses TOKENIZE");
        }
    }

    private boolean requiresKey() {
        if (defaultStrategy == Strategy.TOKENIZE) {
            return true;
        }
        return byType.containsValue(Strategy.TOKENIZE);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Masks everything it is given, needs no key, useful in tests and demos. */
    public static RedactionPolicy maskAll() {
        return builder().defaultStrategy(Strategy.MASK).build();
    }

    public Strategy strategyFor(String type) {
        return byType.getOrDefault(type, defaultStrategy);
    }

    /** Produces the replacement text for one span. */
    public String replacementFor(Entity entity) {
        Strategy strategy = strategyFor(entity.type());
        return switch (strategy) {
            case KEEP -> entity.text();
            case DROP -> "";
            case MASK -> "[" + entity.type() + "]";
            case HASH -> "[" + entity.type() + "_" + shortHex(sha256(entity.text())) + "]";
            case TOKENIZE -> "[" + entity.type() + "_" + shortHex(hmac(entity.text())) + "]";
        };
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(tokenKey, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String shortHex(byte[] digest) {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 3; i++) {
            sb.append(String.format("%02x", digest[i]));
        }
        return sb.toString();
    }

    public static final class Builder {
        private final Map<String, Strategy> byType = new HashMap<>();
        private Strategy defaultStrategy = Strategy.MASK;
        private byte[] tokenKey;

        public Builder defaultStrategy(Strategy strategy) {
            this.defaultStrategy = Objects.requireNonNull(strategy, "strategy");
            return this;
        }

        public Builder with(String type, Strategy strategy) {
            byType.put(Objects.requireNonNull(type), Objects.requireNonNull(strategy));
            return this;
        }

        /** Sets the HMAC key used by {@link Strategy#TOKENIZE}. Load this from a secrets manager. */
        public Builder tokenKey(byte[] key) {
            if (key == null || key.length < 16) {
                throw new IllegalArgumentException("token key must be at least 16 bytes");
            }
            this.tokenKey = key.clone();
            return this;
        }

        public RedactionPolicy build() {
            return new RedactionPolicy(byType, defaultStrategy, tokenKey);
        }
    }

    /** The strategies that remove or obscure the original value. */
    public static EnumSet<Strategy> obscuringStrategies() {
        return EnumSet.of(Strategy.MASK, Strategy.TOKENIZE, Strategy.HASH, Strategy.DROP);
    }
}
