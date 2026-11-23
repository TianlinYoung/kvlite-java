// Verify flush triggers, visibility across generations and the manifest commit boundary.
package kvlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class FlushTest {
    @TempDir Path dir;

    @Test void newerTombstoneStopsLookupAcrossFlushAndReopen() throws IOException {
        try (Storage db = LsmStorage.open(dir)) {
            var first = TableSet.open(dir);
            db.flush();
            assertEquals(first, TableSet.open(dir));
            db.put("a", "old");
            db.put("b", "keep");
            db.flush();
            db.delete("a");
            assertEquals(Optional.empty(), db.get("a"));
            db.flush();
            assertEquals(2, TableSet.open(dir).tables().size());
            assertEquals(Optional.empty(), db.get("a"));
            assertEquals(Optional.of("keep"), db.get("b"));
        }
        try (Storage db = LsmStorage.open(dir)) {
            assertEquals(Optional.empty(), db.get("a"));
            db.put("a", "");
            assertEquals(Optional.of(""), db.get("a"));
            db.flush();
            assertEquals(Optional.of(""), db.get("a"));
        }
    }

    @Test void exactFourMiBOfLiveKeysAndValuesTriggersFlush() throws IOException {
        String value = "x".repeat(1024 * 1024 - 1);
        try (Storage db = LsmStorage.open(dir)) {
            for (String key : List.of("a", "b", "c")) {
                db.put(key, value);
            }
            assertTrue(TableSet.open(dir).tables().isEmpty());
            db.put("d", value);
            TableSet next = TableSet.open(dir);
            assertEquals(1, next.tables().size());
            assertEquals(8, Files.size(next.wal()));
            assertEquals(Optional.of(value), db.get("d"));
        }
    }

    @Test void repeatedOverwriteTriggersSixteenMiBWalLimitIndependently() throws IOException {
        String value = "x".repeat(1024 * 1024);
        try (Storage db = LsmStorage.open(dir)) {
            for (int i = 0; i < 15; i++) {
                db.put("hot", value);
            }
            assertTrue(TableSet.open(dir).tables().isEmpty());
            db.put("hot", value);
            assertEquals(1, TableSet.open(dir).tables().size());
            assertEquals(Optional.of(value), db.get("hot"));
        }
    }

    @Test void emptyKeyTombstoneStillNeedsAFlush() throws IOException {
        try (Storage db = LsmStorage.open(dir)) {
            db.delete("");
            db.flush();
            assertEquals(1, TableSet.open(dir).tables().size());
        }
    }

    @Test void beforeCommitOrphanTableAndWalAreDiscardedAfterReplay() throws IOException {
        try (Storage db = LsmStorage.open(dir)) {
            db.put("k", "from WAL");
        }
        TableSet previous = TableSet.open(dir);
        TableSet pending = previous.afterFlush();
        SSTableWriter.write(pending.tables().getFirst(), List.of(Entry.put("k", "orphan")));
        pending.publishEmptyWal();
        assertNotEquals(pending.manifest(), previous.afterFlush().manifest());
        try (Storage db = LsmStorage.open(dir)) {
            assertEquals(Optional.of("from WAL"), db.get("k"));
            assertFalse(Files.exists(pending.tables().getFirst()));
            assertFalse(Files.exists(pending.wal()));
            db.put("new", "again");
            db.flush();
        }
        try (Storage db = LsmStorage.open(dir)) {
            assertEquals(Optional.of("again"), db.get("new"));
        }
    }

    @Test void afterCommitOnlyNewWalAndListedTablesAreLive() throws IOException {
        try (Storage db = LsmStorage.open(dir)) {
            db.put("k", "old WAL");
        }
        TableSet old = TableSet.open(dir);
        TableSet next = old.afterFlush();
        SSTableWriter.write(next.tables().getFirst(), List.of(Entry.put("k", "committed table")));
        next.publishEmptyWal();
        next.commit();
        try (Storage db = LsmStorage.open(dir)) {
            assertEquals(Optional.of("committed table"), db.get("k"));
            assertFalse(Files.exists(old.manifest()));
            assertFalse(Files.exists(old.wal()));
            db.put("k", "next write");
            db.flush();
        }
        try (Storage db = LsmStorage.open(dir)) {
            assertEquals(Optional.of("next write"), db.get("k"));
        }
    }

    @Test void duplicateReferencesAreRejectedAndManifestOrderControlsRecency() throws IOException {
        try (Storage db = LsmStorage.open(dir)) {
            db.put("same", "first");
            db.flush();
            db.put("same", "second");
            db.flush();
        }
        TableSet current = TableSet.open(dir);
        List<String> lines = Files.readAllLines(current.manifest());
        String newest = lines.get(2);
        lines.set(2, lines.get(3));
        Files.writeString(current.manifest(), String.join("\n", lines) + "\n");
        assertThrows(IOException.class, () -> LsmStorage.open(dir));
        lines.set(3, newest);
        Files.writeString(current.manifest(), String.join("\n", lines) + "\n");
        try (Storage db = LsmStorage.open(dir)) {
            assertEquals(Optional.of("first"), db.get("same"));
        }
    }

    @Test void badCommittedFooterPreventsCleanupAndWalRepair() throws IOException {
        try (Storage db = LsmStorage.open(dir)) {
            db.put("k", "v");
            db.flush();
        }
        TableSet current = TableSet.open(dir);
        Path table = current.tables().getFirst();
        byte[] bytes = Files.readAllBytes(table);
        bytes[bytes.length - 1] ^= 1;
        Files.write(table, bytes);
        Path orphan = Files.createFile(dir.resolve("wal-0000000000000000099.log"));
        Files.write(current.wal(), new byte[]{1}, java.nio.file.StandardOpenOption.APPEND);
        assertThrows(IOException.class, () -> LsmStorage.open(dir));
        assertTrue(Files.exists(orphan));
        assertEquals(9, Files.size(current.wal()));
    }
}
