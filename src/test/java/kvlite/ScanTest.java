// Verify public range semantics across sparse index blocks, disk tables and memory.
package kvlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScanTest {
    @TempDir Path dir;

    @Test void rangesUseInclusiveLowerAndExclusiveUpperBounds() throws IOException {
        try (Storage db = LsmStorage.open(dir)) {
            for (int i = 0; i < 70; i++) {
                db.put(String.format(Locale.ROOT, "k%02d", i), "disk");
            }
            db.flush(); // k00, k32 and k64 are the samples.

            assertEquals(List.of(Map.entry("k00", "disk")), db.scan("", "k01"));
            assertEquals(List.of(), db.scan("", "k00"));
            assertEquals(List.of(Map.entry("k31", "disk")), db.scan("k30x", "k32"));
            assertEquals(List.of(Map.entry("k32", "disk"), Map.entry("k33", "disk")), db.scan("k32", "k34"));
            assertEquals(List.of(Map.entry("k69", "disk")), db.scan("k68x", "z"));
            assertEquals(List.of(), db.scan("x", "z"));
        }
    }

    @Test void scanReturnsAnIndependentSnapshotOfVisibleValues() throws IOException {
        try (Storage db = LsmStorage.open(dir)) {
            db.put("", "");
            db.put("gone", "disk");
            db.put("shared", "old");
            db.flush();
            db.put("shared", "newer disk");
            db.put("disk-only", "value");
            db.delete("gone");
            db.flush();
            db.put("shared", "memory");
            db.delete("disk-only");
            db.put("😀", "emoji");
            db.put("\ue000", "private");

            var snapshot = db.scan("", "\uffff");
            assertThrows(UnsupportedOperationException.class, () -> snapshot.add(Map.entry("extra", "value")));
            db.delete("shared");
            assertEquals(List.of(Map.entry("", ""), Map.entry("shared", "memory"),
                    Map.entry("😀", "emoji"), Map.entry("\ue000", "private")), snapshot);
            assertEquals(List.of(Map.entry("😀", "emoji")), db.scan("😀", "\ue000"));
        }
    }

    @Test void boundsAreValidatedEvenOnAnEmptyStore() throws IOException {
        Storage db = LsmStorage.open(dir);
        try (db) {
            assertEquals(List.of(), db.scan("", "z"));
            assertEquals(List.of(), db.scan("a", "a"));
            assertThrows(IllegalArgumentException.class, () -> db.scan("b", "a"));
            assertThrows(NullPointerException.class, () -> db.scan("", null));
            assertThrows(IllegalArgumentException.class, () -> db.scan("\udfff", "z"));
        }
        assertThrows(IllegalStateException.class, () -> db.scan("", "z"));
    }
}
