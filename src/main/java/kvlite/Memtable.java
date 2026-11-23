// Keep the latest entry per key in Java string order.
package kvlite;

import java.nio.charset.StandardCharsets;
import java.util.TreeMap;
import java.util.Optional;

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

    Optional<String> get(String key) {
        Entry entry = entries.get(key);
        return entry == null ? Optional.empty() : entry.value();
    }

    long encodedBytes() {
        return encodedBytes;
    }

    private static long sizeOf(Entry entry) {
        return entry.key().getBytes(StandardCharsets.UTF_8).length
                + entry.value().orElse("").getBytes(StandardCharsets.UTF_8).length;
    }
}
