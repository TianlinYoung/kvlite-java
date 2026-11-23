// Exercise the public API across writes, invalid inputs, close and reopen.
package kvlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class StorageTest {
    @TempDir Path dir;

    @Test void reopenPreservesLatestValuesAndDeletes() throws IOException {
        try (Storage db = LsmStorage.open(dir)) {
            db.put("", "");
            db.put("猫", "first");
            db.put("猫", "😀");
            db.put("gone", "old");
            db.delete("gone");
            db.delete("missing");
            assertEquals(Optional.of("😀"), db.get("猫"));
            assertThrows(NullPointerException.class, () -> db.put("猫", null));
            assertThrows(NullPointerException.class, () -> db.get(null));
            assertThrows(IllegalArgumentException.class, () -> db.put("猫", "\udfff"));
        }
        Storage db = LsmStorage.open(dir);
        try (db) {
            assertEquals(Optional.of(""), db.get(""));
            assertEquals(Optional.of("😀"), db.get("猫"));
            assertEquals(Optional.empty(), db.get("gone"));
            assertEquals(Optional.empty(), db.get("missing"));
        }
        assertThrows(IllegalStateException.class, () -> db.get("x"));
        assertThrows(IllegalStateException.class, () -> db.delete("x"));
    }
}
