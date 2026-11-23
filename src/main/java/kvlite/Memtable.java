// Keep the latest entry per key in Java string order.
package kvlite;

import java.nio.charset.StandardCharsets;
import java.util.TreeMap;

final class Memtable {
    private final TreeMap<String, Entry> entries = new TreeMap<>();
    private long encodedBytes;

    void apply(Entry entry) {
        Entry previous = entries.put(entry.key(), entry);
        if (previous != null) {
            encodedBytes -= sizeOf(previous);
        }
        encodedBytes += sizeOf(entry);
    }

    Entry get(String key) {
        return entries.get(key);
    }

    Iterable<Entry> sortedEntries() {
        return entries.values();
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    long encodedBytes() {
        return encodedBytes;
    }

    private static long sizeOf(Entry entry) {
        return entry.key().getBytes(StandardCharsets.UTF_8).length
                + entry.value().orElse("").getBytes(StandardCharsets.UTF_8).length;
    }
}
