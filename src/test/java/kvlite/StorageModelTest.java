// Compare mixed operations and recovery with an in-memory model using a reproducible seed.
package kvlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class StorageModelTest {
    private static final long SEED = 0x4b564c495445L;
    private static final int STEPS = 5_000;
    private static final String UPPER = "\uffff";

    private enum Operation { PUT, DELETE, GET, FLUSH, COMPACT, SCAN, REOPEN }

    @TempDir Path dir;

    @Test void mixedOperationsAgreeWithTreeMap() throws IOException {
        var random = new Random(SEED);
        var keys = new ArrayList<>(List.of("", "猫", "😀", "\ue000"));
        for (int i = 0; i < 96; i++) {
            keys.add(String.format(Locale.ROOT, "k%02d", i));
        }
        var model = new TreeMap<String, String>();
        var seen = java.util.EnumSet.noneOf(Operation.class);
        Operation[] operations = Operation.values();

        Storage db = LsmStorage.open(dir);
        try {
            for (int step = 0; step < STEPS; step++) {
                Operation operation = operations[random.nextInt(operations.length)];
                seen.add(operation);
                String key = keys.get(random.nextInt(keys.size()));
                String second = random.nextInt(5) == 0 ? UPPER : keys.get(random.nextInt(keys.size()));
                String value = random.nextInt(5) == 0 ? "" : "value " + step;
                try {
                    switch (operation) {
                        case PUT -> {
                            db.put(key, value);
                            model.put(key, value);
                        }
                        case DELETE -> {
                            db.delete(key);
                            model.remove(key);
                        }
                        case GET -> assertEquals(Optional.ofNullable(model.get(key)), db.get(key));
                        case FLUSH -> {
                            db.flush();
                            assertFullMapping(db, model);
                        }
                        case COMPACT -> {
                            db.compact();
                            assertFullMapping(db, model);
                        }
                        case SCAN -> {
                            boolean ascending = key.compareTo(second) <= 0;
                            String from = ascending ? key : second;
                            String to = ascending ? second : key;
                            assertEquals(List.copyOf(model.subMap(from, true, to, false).entrySet()),
                                    db.scan(from, to));
                        }
                        case REOPEN -> {
                            db.close();
                            db = LsmStorage.open(dir);
                            assertFullMapping(db, model);
                        }
                    }
                } catch (IOException | RuntimeException | AssertionError failure) {
                    throw new AssertionError("seed=" + SEED + ", step=" + step + ", operation=" + operation
                            + ", key=" + key + ", second=" + second + ", value=" + value, failure);
                }
            }
        } finally {
            db.close();
        }
        assertEquals(java.util.EnumSet.allOf(Operation.class), seen, "seed=" + SEED);
    }

    private static void assertFullMapping(Storage db, Map<String, String> model) throws IOException {
        assertEquals(List.copyOf(model.entrySet()), db.scan("", UPPER));
    }
}
