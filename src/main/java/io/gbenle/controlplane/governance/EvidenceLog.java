package io.gbenle.controlplane.governance;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An append-only log in which each record commits to the one before it.
 *
 * <p>Every record stores the hash of its predecessor and a hash over its own contents
 * including that predecessor hash. Altering any earlier record changes its hash, which
 * breaks the link the next record holds, and {@link #verify()} reports the position
 * where the chain first fails. The property this buys is modest and worth being precise
 * about: it does not prevent tampering, and it does not help if an attacker can rewrite
 * the whole log and recompute every hash. What it gives you is that a silent edit of one
 * row, the realistic failure in a shared operational datastore, becomes detectable.
 *
 * <p>For a durable deployment, persist each record as it is appended and anchor the head
 * hash somewhere outside the same trust boundary.
 *
 * <p>Hash input is a big-endian binary encoding: sequence and epoch seconds as 64-bit integers,
 * nanoseconds as a 32-bit integer, then event, detail and predecessor hash as strings. Each string
 * has a 32-bit code-unit count followed by its 16-bit Java characters. Lengths preserve field
 * boundaries even when text contains separators; character encoding preserves unpaired surrogates.
 * This replaces the earlier delimiter-separated, millisecond-precision encoding, so hashes differ.
 * The log is in memory and has no import format; externally persisted hashes must retain their
 * original encoding for verification.
 *
 * <p>This implementation is synchronized and safe for concurrent appends.
 */
public final class EvidenceLog {

    /** Hash value standing in for the predecessor of the first record. */
    public static final String GENESIS = "0".repeat(64);

    /**
     * One entry in the chain.
     *
     * @param sequence     position in the log, starting at zero
     * @param recordedAt   when the entry was appended
     * @param event        short event name, for example {@code MODEL_PROMOTION_EVALUATED}
     * @param detail       free-text detail
     * @param previousHash hash of the preceding record, or {@link #GENESIS}
     * @param hash         hash over this record's own fields
     */
    public record EvidenceRecord(
            long sequence,
            Instant recordedAt,
            String event,
            String detail,
            String previousHash,
            String hash) { }

    private final List<EvidenceRecord> records = new ArrayList<>();

    public synchronized EvidenceRecord append(String event, String detail, Instant at) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(at, "at");
        String safeDetail = detail == null ? "" : detail;
        long sequence = records.size();
        String previousHash = records.isEmpty() ? GENESIS : records.get(records.size() - 1).hash();
        String hash = digest(sequence, at, event, safeDetail, previousHash);
        EvidenceRecord record = new EvidenceRecord(sequence, at, event, safeDetail, previousHash, hash);
        records.add(record);
        return record;
    }

    public synchronized List<EvidenceRecord> records() {
        return List.copyOf(records);
    }

    public synchronized int size() {
        return records.size();
    }

    /** Hash of the most recent record, or {@link #GENESIS} when the log is empty. */
    public synchronized String head() {
        return records.isEmpty() ? GENESIS : records.get(records.size() - 1).hash();
    }

    /**
     * Recomputes the whole chain.
     *
     * @return the sequence number of the first record that fails verification,
     *         or -1 when the chain is intact
     */
    public synchronized long verify() {
        String expectedPrevious = GENESIS;
        for (EvidenceRecord record : records) {
            if (!record.previousHash().equals(expectedPrevious)) {
                return record.sequence();
            }
            String expectedHash = digest(record.sequence(), record.recordedAt(),
                    record.event(), record.detail(), record.previousHash());
            if (!record.hash().equals(expectedHash)) {
                return record.sequence();
            }
            expectedPrevious = record.hash();
        }
        return -1L;
    }

    private static String digest(long sequence, Instant at, String event,
                                 String detail, String previousHash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(Long.BYTES * 2 + Integer.BYTES)
                    .putLong(sequence)
                    .putLong(at.getEpochSecond())
                    .putInt(at.getNano())
                    .array());
            updateString(digest, event);
            updateString(digest, detail);
            updateString(digest, previousHash);
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void updateString(MessageDigest digest, String value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length()).array());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            digest.update((byte) (character >>> 8));
            digest.update((byte) character);
        }
    }
}
