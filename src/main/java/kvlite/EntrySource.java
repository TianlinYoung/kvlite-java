// Pull one physical record at a time from a sorted source that can fail on I/O.
package kvlite;

import java.io.IOException;

@FunctionalInterface
interface EntrySource {
    // Null means end of source. A tombstone is still an Entry.
    Entry next() throws IOException;
}
