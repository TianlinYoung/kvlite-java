// Pin benchmark counts, repeatable hits and reporting without assuming a throughput.
package kvlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkWorkloadTest {
    @TempDir Path dir;

    @Test void measuredWritesAreFlushedBeforeTheReproducibleReads() throws IOException {
        var sample = BenchmarkWorkload.measure(dir, 7, 16);
        assertEquals(7, sample.writes());
        assertEquals(16, sample.reads());
        assertEquals(10, sample.hits());
        assertEquals(0.625, sample.hitRate());
        assertTrue(sample.writeOpsPerSecond() > 0);
        assertTrue(sample.readOpsPerSecond() > 0);
        try (Storage storage = LsmStorage.open(dir)) {
            assertEquals(new Stats(1, 0, 8), storage.stats());
            assertEquals(Optional.of("v".repeat(128)), storage.get("key-6"));
        }
    }

    @Test void reportIncludesMeasurementsAndWorkloadSizes() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new PrintStream(bytes, false, UTF_8)) {
            BenchmarkWorkload.run(output, 3, 7, 16);
        }
        String report = bytes.toString(UTF_8);
        assertTrue(report.contains("seed=12345 warmupWrites=3 warmupReads=3 writes=7 reads=16 valueBytes=128"));
        assertTrue(report.contains("write: "));
        assertTrue(report.contains("read: "));
        assertTrue(report.contains("hits=10/16 (62.50%)"));
        assertTrue(report.contains("filesystem="));
    }

    @Test void invalidCountsAreRejectedBeforeGatheringData() {
        assertThrows(IllegalArgumentException.class, () -> BenchmarkWorkload.measure(dir, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkWorkload.measure(dir, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> BenchmarkWorkload.measure(dir, Integer.MAX_VALUE, 1));
    }
}
