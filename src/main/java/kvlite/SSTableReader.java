// Read physical entries, including tombstones, from one immutable table.
package kvlite;

import java.io.Closeable;
import java.io.IOException;

interface SSTableReader extends Closeable {
    Entry get(String key) throws IOException;
    EntrySource entries();
    EntrySource range(String fromInclusive, String toExclusive);
}
