package io.gbenle.controlplane;

import io.gbenle.controlplane.governance.EvidenceLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EvidenceLogTest {

    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    @Test
    @DisplayName("moving a separator between event and detail changes the audit hash")
    void preservesFieldBoundaries() {
        EvidenceLog first = new EvidenceLog();
        EvidenceLog second = new EvidenceLog();

        String firstHash = first.append("event\u001fpart", "detail", NOW).hash();
        String secondHash = second.append("event", "part\u001fdetail", NOW).hash();

        assertNotEquals(firstHash, secondHash);
        assertEquals(-1L, first.verify());
        assertEquals(-1L, second.verify());
    }

    @Test
    @DisplayName("audit hashes distinguish timestamps within the same millisecond")
    void preservesNanoseconds() {
        EvidenceLog first = new EvidenceLog();
        EvidenceLog second = new EvidenceLog();

        String firstHash = first.append("event", "detail", NOW.plusNanos(1)).hash();
        String secondHash = second.append("event", "detail", NOW.plusNanos(2)).hash();

        assertNotEquals(firstHash, secondHash);
        assertEquals(-1L, first.verify());
        assertEquals(-1L, second.verify());
    }

    @Test
    @DisplayName("audit timestamps support the full Instant range without millisecond overflow")
    void supportsInstantExtremes() {
        EvidenceLog log = new EvidenceLog();

        EvidenceLog.EvidenceRecord earliest = log.append("event", "detail", Instant.MIN);
        EvidenceLog.EvidenceRecord latest = log.append("event", "detail", Instant.MAX);

        assertEquals(Instant.MIN, earliest.recordedAt());
        assertEquals(Instant.MAX, latest.recordedAt());
        assertEquals(earliest.hash(), latest.previousHash());
        assertEquals(-1L, log.verify());
    }

    @Test
    @DisplayName("unpaired surrogate characters remain distinguishable in audit text")
    void preservesAllJavaCharacters() {
        EvidenceLog first = new EvidenceLog();
        EvidenceLog second = new EvidenceLog();

        assertNotEquals(first.append("event", "\uD800", NOW).hash(),
                second.append("event", "\uD801", NOW).hash());
        assertEquals(-1L, first.verify());
        assertEquals(-1L, second.verify());
    }

    @Test
    @DisplayName("replaying identical records reproduces hashes while repeated appends advance the chain")
    void producesStableLinkedHashes() {
        EvidenceLog first = new EvidenceLog();
        EvidenceLog replay = new EvidenceLog();

        EvidenceLog.EvidenceRecord initial = first.append("event", "detail", NOW);
        EvidenceLog.EvidenceRecord repeated = first.append("event", "detail", NOW);

        assertEquals(initial.hash(), replay.append("event", "detail", NOW).hash());
        assertEquals(repeated.hash(), replay.append("event", "detail", NOW).hash());
        assertEquals(initial.hash(), repeated.previousHash());
        assertNotEquals(initial.hash(), repeated.hash());
        assertEquals(-1L, first.verify());
        assertEquals(-1L, replay.verify());
    }
}
