// Check replacement and tombstone accounting using UTF-8 byte lengths.
package kvlite;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class MemtableTest {
    @Test void replacementSubtractsThePreviousSize() {
        Memtable table = new Memtable();
        table.apply(Entry.put("猫", "😀"));
        table.apply(Entry.put("a", ""));
        assertEquals(8, table.encodedBytes());
        table.apply(Entry.put("猫", "x"));
        assertEquals(5, table.encodedBytes());
        table.apply(Entry.delete("猫"));
        assertEquals(4, table.encodedBytes());
        assertEquals(Optional.empty(), table.get("猫"));
        assertEquals(Optional.of(""), table.get("a"));
    }
}
