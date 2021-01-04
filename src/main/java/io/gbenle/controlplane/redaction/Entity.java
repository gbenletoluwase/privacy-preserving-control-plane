package io.gbenle.controlplane.redaction;

/**
 * A span of text identified as sensitive.
 *
 * @param type       entity label, for example EMAIL or SSN
 * @param start      inclusive start offset in the source text
 * @param end        exclusive end offset in the source text
 * @param text       the matched substring
 * @param confidence detector confidence in [0, 1]
 * @param detector   name of the recognizer that produced this span
 */
public record Entity(
        String type,
        int start,
        int end,
        String text,
        double confidence,
        String detector) implements Comparable<Entity> {

    public Entity {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("invalid span [" + start + "," + end + ")");
        }
        if (!(confidence >= 0.0 && confidence <= 1.0)) {
            throw new IllegalArgumentException("confidence must be in [0,1], got " + confidence);
        }
    }

    public int length() {
        return end - start;
    }

    /** True when this span shares at least one character with {@code other}. */
    public boolean overlaps(Entity other) {
        return start < other.end && other.start < end;
    }

    /** Orders by start offset, then by descending confidence, then by descending length. */
    @Override
    public int compareTo(Entity other) {
        int byStart = Integer.compare(start, other.start);
        if (byStart != 0) {
            return byStart;
        }
        int byConfidence = Double.compare(other.confidence, confidence);
        if (byConfidence != 0) {
            return byConfidence;
        }
        return Integer.compare(other.length(), length());
    }
}
