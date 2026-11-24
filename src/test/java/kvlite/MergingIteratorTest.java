// Verify newest-first ties, tombstones and empty input in the streaming merge.
package kvlite;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MergingIteratorTest {
    @Test void newestRecordWinsAndEverySourceAdvancesPastTheKey() throws IOException {
        Entry newest = Entry.put("b", "new");
        Entry deleted = Entry.delete("c");
        Entry first = Entry.put("a", "old only");
        Entry last = Entry.put("d", "middle only");
        var merged = new MergingIterator(List.of(
                source(newest, deleted),
                source(),
                source(Entry.put("b", "middle"), Entry.put("c", "middle"), last),
                source(first, Entry.put("b", "old"), Entry.put("c", "old"))));

        assertEquals(first, merged.next());
        assertEquals(newest, merged.next());
        assertEquals(deleted, merged.next());
        assertEquals(last, merged.next());
        assertNull(merged.next());
    }

    @Test void emptySourcesHaveNoWinner() throws IOException {
        assertNull(new MergingIterator(List.of()).next());
        assertNull(new MergingIterator(List.of(source(), source())).next());
    }

    private static EntrySource source(Entry... entries) {
        var iterator = List.of(entries).iterator();
        return () -> iterator.hasNext() ? iterator.next() : null;
    }
}
