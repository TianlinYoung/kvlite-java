// Public operations on a single-threaded string store.
package kvlite;

import java.io.Closeable;
import java.io.IOException;
import java.util.Optional;

public interface Storage extends Closeable {
    void put(String key, String value) throws IOException;
    Optional<String> get(String key);
    void delete(String key) throws IOException;
}
