// Verify full compaction, WAL preservation and both sides of the manifest commit.
package kvlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CompactionTest {
    @TempDir Path dir;

    @Test void noInputLeavesManifestAndLiveWalAlone() throws IOException {
        try (Storage db = LsmStorage.open(dir)) {
            db.put("k", "memory");
            TableSet before = TableSet.open(dir);
            byte[] walBytes = Files.readAllBytes(before.wal());
            db.compact();
            assertEquals(before, TableSet.open(dir));
            assertArrayEquals(walBytes, Files.readAllBytes(before.wal()));
            assertEquals(Optional.of("memory"), db.get("k"));
        }
    }

    @Test void allDeletedCommitsAnEmptyTableListAndRemovesOldTables() throws IOException {
        TableSet after;
        try (Storage db = LsmStorage.open(dir)) {
            db.put("a", "old");
            db.flush();
            db.delete("a");
            db.delete("b");
            db.flush();
            TableSet before = TableSet.open(dir);
            db.compact();
            after = TableSet.open(dir);
            assertNotEquals(before.manifest(), after.manifest());
            assertEquals(before.wal(), after.wal());
            assertEquals(List.of(), after.tables());
            assertEquals(List.of(), db.scan("", "z"));
            assertRetired(before);
            try (var paths = Files.list(dir)) {
                assertEquals(2, paths.count());
            }
        }
        try (Storage db = LsmStorage.open(dir)) {
            assertEquals(after, TableSet.open(dir));
            assertEquals(Optional.empty(), db.get("a"));
        }
    }

    @Test void repeatedVersionsCollapseWhileUnflushedWritesStayInTheWal() throws IOException {
        try (Storage db = LsmStorage.open(dir)) {
            for (int version = 0; version < 5; version++) {
                db.put("hot", "v" + version);
                db.put("hidden", "v" + version);
                db.flush();
            }
            db.put("hot", "memory");
            db.delete("hidden");
            TableSet before = TableSet.open(dir);
            byte[] walBytes = Files.readAllBytes(before.wal());

            db.compact();

            TableSet after = TableSet.open(dir);
            assertEquals(before.wal(), after.wal());
            assertArrayEquals(walBytes, Files.readAllBytes(after.wal()));
            assertEquals(1, after.tables().size());
            assertRetired(before);
            try (var disk = new FileSSTableReader(after.tables().getFirst())) {
                var records = disk.entries();
                assertEquals(Entry.put("hidden", "v4"), records.next());
                assertEquals(Entry.put("hot", "v4"), records.next());
                assertNull(records.next());
            }
            assertEquals(List.of(Map.entry("hot", "memory")), db.scan("", "z"));
            db.put("later", "also in WAL");
        }
        try (Storage db = LsmStorage.open(dir)) {
            assertEquals(List.of(Map.entry("hot", "memory"), Map.entry("later", "also in WAL")),
                    db.scan("", "z"));
            db.flush();
            db.compact();
            assertEquals(1, TableSet.open(dir).tables().size());
        }
    }

    @Test void reopenBeforeCommitKeepsInputsAndCleansPublishedOrphan() throws IOException {
        TableSet before = prepareInputs();
        TableSet pending = stageReplacement(before);
        Path orphan = pending.tables().getFirst();
        byte[] walBytes = Files.readAllBytes(before.wal());

        try (Storage db = LsmStorage.open(dir)) {
            assertEquals(before, TableSet.open(dir));
            assertFalse(Files.exists(orphan));
            assertArrayEquals(walBytes, Files.readAllBytes(before.wal()));
            assertEquals(List.of(Map.entry("live", "disk"), Map.entry("pending", "wal")), db.scan("", "z"));
        }
    }

    @Test void reopenAfterCommitKeepsReplacementAndCleansRetiredInputs() throws IOException {
        TableSet before = prepareInputs();
        TableSet replacement = stageReplacement(before);
        byte[] walBytes = Files.readAllBytes(before.wal());
        replacement.commit(); // Simulate an exit after publication but before switching handles.

        try (Storage db = LsmStorage.open(dir)) {
            assertEquals(replacement, TableSet.open(dir));
            assertRetired(before);
            assertArrayEquals(walBytes, Files.readAllBytes(replacement.wal()));
            assertEquals(List.of(Map.entry("live", "disk"), Map.entry("pending", "wal")), db.scan("", "z"));
            db.put("live", "updated");
        }
        try (Storage db = LsmStorage.open(dir)) {
            assertEquals(Optional.of("updated"), db.get("live"));
        }
    }

    private TableSet prepareInputs() throws IOException {
        try (Storage db = LsmStorage.open(dir)) {
            db.put("gone", "old");
            db.flush();
            db.delete("gone");
            db.put("live", "disk");
            db.flush();
            db.put("pending", "wal");
        }
        return TableSet.open(dir);
    }

    private static TableSet stageReplacement(TableSet before) throws IOException {
        TableSet pending = before.afterCompaction();
        SSTableWriter.write(pending.tables().getFirst(), List.of(Entry.put("live", "disk")));
        return pending;
    }

    private static void assertRetired(TableSet old) {
        assertFalse(Files.exists(old.manifest()));
        for (Path path : old.tables()) {
            assertFalse(Files.exists(path), path::toString);
        }
    }
}
