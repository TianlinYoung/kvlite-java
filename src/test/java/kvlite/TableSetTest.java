// Recreate startup crash states and check manifest selection and publication.
package kvlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class TableSetTest {
    @TempDir Path dir;
    private static final String WAL = "wal-0000000000000000001.log";
    private static final String MANIFEST = "manifest-0000000000000000001.txt";
    private static final String CONTENT = "KVMANIFEST 1\n" + WAL + "\n";

    @Test void manifestIsPublishedBeforeFirstWriteAndOnlyOwnedTempsAreRemoved() throws IOException {
        Files.writeString(dir.resolve(WAL + ".tmp"), "partial");
        Files.writeString(dir.resolve("notes.tmp"), "mine");
        try (var db = LsmStorage.open(dir)) {
            assertEquals(CONTENT, Files.readString(dir.resolve(MANIFEST)));
            assertArrayEquals(Wal.HEADER, Files.readAllBytes(dir.resolve(WAL)));
            assertFalse(Files.exists(dir.resolve(WAL + ".tmp")));
            assertTrue(Files.exists(dir.resolve("notes.tmp")));
            db.put("a", "1");
        }
    }

    @Test void existingInitialWalIsReused() throws IOException {
        Path wal = Files.write(dir.resolve(WAL), Wal.HEADER);
        var oldTime = Files.getLastModifiedTime(wal);
        Files.writeString(dir.resolve(MANIFEST + ".tmp"), "partial");
        try (var db = LsmStorage.open(dir)) {
            assertEquals(oldTime, Files.getLastModifiedTime(wal));
            assertEquals(CONTENT, Files.readString(dir.resolve(MANIFEST)));
        }
    }

    @Test void cannotReinitializeAStoreWithUnexplainedData() throws IOException {
        Path wal = dir.resolve(WAL);
        Files.writeString(wal, "bad header");
        assertThrows(IOException.class, () -> LsmStorage.open(dir));
        Files.write(wal, Wal.HEADER);
        Files.write(wal, new byte[]{1}, StandardOpenOption.APPEND);
        assertThrows(IOException.class, () -> LsmStorage.open(dir));
        Files.write(wal, Wal.HEADER);
        Files.createFile(dir.resolve("sst-0000000000000000001.sst"));
        assertThrows(IOException.class, () -> LsmStorage.open(dir));
        assertFalse(Files.exists(dir.resolve(MANIFEST)));
    }

    @Test void highestManifestIsAuthoritativeAndCleanupFollowsValidation() throws IOException {
        TableSet first = TableSet.open(dir);
        Path latest = dir.resolve("manifest-0000000000000000002.txt");
        for (String invalid : new String[]{"bad", CONTENT + "sst-0000000000000000001.sst\n",
                CONTENT.replace(WAL, "../outside"), CONTENT.replace(WAL,"wal-0000000000000000002.log")}) {
            Files.writeString(latest, invalid);
            assertThrows(IOException.class, () -> LsmStorage.open(dir));
            assertTrue(Files.exists(first.manifest()));
        }
        Files.writeString(latest, CONTENT);
        try (var db = LsmStorage.open(dir)) {
            assertFalse(Files.exists(first.manifest()));
            assertTrue(Files.exists(latest));
        }
    }
}
