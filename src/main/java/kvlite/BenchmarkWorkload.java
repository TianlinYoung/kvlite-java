// Prepare deterministic work, measure writes and random reads, and report throughput.
package kvlite;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

final class BenchmarkWorkload {
    static final long SEED = 12345L;
    private static final String VALUE = "v".repeat(128);

    record Sample(int writes, int reads, int hits, long writeNanos, long readNanos) {
        double writeOpsPerSecond() {
            return writes * 1_000_000_000.0 / writeNanos;
        }

        double readOpsPerSecond() {
            return reads * 1_000_000_000.0 / readNanos;
        }

        double hitRate() {
            return hits / (double) reads;
        }
    }

    static void run(PrintStream out, int warmup, int writes, int reads) throws IOException {
        Path directory = Files.createTempDirectory("kvlite-benchmark-");
        Path warmupDir = directory.resolve("warmup");
        Path measuredDir = directory.resolve("measured");
        try {
            // Warm up the same write and disk-read paths in a separate store.
            measure(warmupDir, warmup, warmup);
            Sample sample = measure(measuredDir, writes, reads);
            out.printf(Locale.ROOT, "seed=%d warmupWrites=%d warmupReads=%d writes=%d reads=%d valueBytes=%d%n",
                    SEED, warmup, warmup, writes, reads, VALUE.length());
            out.printf(Locale.ROOT, "write: %.2f ops/s (%.6f s)%n", sample.writeOpsPerSecond(),
                    sample.writeNanos() / 1_000_000_000.0);
            out.printf(Locale.ROOT, "read: %.2f ops/s (%.6f s), hits=%d/%d (%.2f%%)%n",
                    sample.readOpsPerSecond(), sample.readNanos() / 1_000_000_000.0,
                    sample.hits(), sample.reads(), sample.hitRate() * 100);
            out.printf("OS=%s %s %s; JDK=%s (%s); filesystem=%s; temp=%s%n",
                    System.getProperty("os.name"), System.getProperty("os.version"),
                    System.getProperty("os.arch"), System.getProperty("java.version"),
                    System.getProperty("java.vendor"), Files.getFileStore(directory).type(), directory.getParent());
        } finally {
            // Each work directory contains only files created by this run. Close before deleting on Windows.
            removeStore(warmupDir);
            removeStore(measuredDir);
            Files.delete(directory);
        }
    }

    static Sample measure(Path directory, int writes, int reads) throws IOException {
        if (writes <= 0 || reads <= 0 || writes > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException("Expected positive counts and at most Integer.MAX_VALUE/2 writes");
        }
        List<String> writeKeys = new ArrayList<>(writes);
        for (int i = 0; i < writes; i++) {
            writeKeys.add("key-" + i);
        }
        List<String> readKeys = new ArrayList<>(reads);
        Random random = new Random(SEED);
        for (int i = 0; i < reads; i++) {
            readKeys.add("key-" + random.nextInt(2 * writes));
        }
        // Use a fresh store. Key generation, explicit flush, open and close are outside both timers.
        try (Storage store = LsmStorage.open(directory)) {
            long start = System.nanoTime();
            for (String key : writeKeys) {
                store.put(key, VALUE);
            }
            long writeNanos = System.nanoTime() - start;
            store.flush();

            int hits = 0;
            start = System.nanoTime();
            for (String key : readKeys) {
                if (store.get(key).isPresent()) {
                    hits++;
                }
            }
            long readNanos = System.nanoTime() - start;
            return new Sample(writes, reads, hits, writeNanos, readNanos);
        }
    }

    private static void removeStore(Path directory) throws IOException {
        if (Files.exists(directory)) {
            // Close the listing before removing files or the directory itself.
            List<Path> files;
            try (var paths = Files.list(directory)) {
                files = paths.toList();
            }
            for (Path path : files) {
                Files.delete(path);
            }
            Files.delete(directory);
        }
    }
}
