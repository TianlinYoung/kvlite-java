// Look up the physical entry, including a tombstone, in one immutable table.
package kvlite;

import java.io.Closeable;
import java.io.IOException;

interface SSTableReader extends Closeable {
    Entry get(String key) throws IOException;
}
