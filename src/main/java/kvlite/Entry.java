// One key's value or deletion marker, shared by memory and disk.
package kvlite;

import java.util.Optional;

record Entry(String key, Optional<String> value) {
    static Entry put(String key, String value) {
        return new Entry(key, Optional.of(value));
    }

    static Entry delete(String key) {
        return new Entry(key, Optional.empty());
    }
}
