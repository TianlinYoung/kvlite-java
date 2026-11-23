// Publish and recover the manifest that names the live WAL and newest-first disk tables.
package kvlite;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.math.BigInteger;
import java.util.regex.Pattern;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.*;

record TableSet(Path manifest, Path wal, List<Path> tables) {
    // The negative lookahead (?!0{19}) excludes generation zero.
    private static final String ID = "(?!0{19})[0-9]{19}";
    private static final Pattern MANIFEST = Pattern.compile("manifest-" + ID + "[.]txt");
    private static final Pattern LOG = Pattern.compile("wal-" + ID + "[.]log");
    private static final Pattern TABLE = Pattern.compile("sst-" + ID + "[.]sst");
    private static final String FIRST = "0000000000000000001";
    private static final String MAGIC = "KVMANIFEST 1\n";

    static TableSet open(Path dir) throws IOException {
        Files.createDirectories(dir);
        List<String> names = names(dir);
        // Fixed-width names sort in the same order as their generation numbers.
        var latest = names.stream().filter(MANIFEST.asMatchPredicate()).max(String::compareTo);
        if (latest.isPresent()) {
            Path manifest = dir.resolve(latest.get());
            String[] lines = Files.readString(manifest).split("\n", -1);
            if (lines.length < 3 || !lines[0].equals("KVMANIFEST 1")
                    || !LOG.matcher(lines[1]).matches() || !lines[lines.length - 1].isEmpty()) {
                throw new IOException("Invalid or unsupported manifest: " + manifest);
            }
            var tables = new ArrayList<Path>();
            var seen = new HashSet<String>();
            for (int i = 2; i < lines.length - 1; i++) {
                if (!TABLE.matcher(lines[i]).matches() || !seen.add(lines[i])) {
                    throw new IOException("Invalid or duplicate table: " + lines[i]);
                }
                tables.add(dir.resolve(lines[i]));
            }
            return new TableSet(manifest, dir.resolve(lines[1]), List.copyOf(tables));
        }

        Path wal = dir.resolve("wal-" + FIRST + ".log");
        for (String name : names) {
            if (TABLE.matcher(name).matches() || LOG.matcher(name).matches()
                    && !name.equals(wal.getFileName().toString())) {
                throw new IOException("Missing manifest for " + name);
            }
        }
        if (Files.exists(wal)) {
            if (Files.size(wal) != Wal.HEADER.length || !Arrays.equals(Files.readAllBytes(wal), Wal.HEADER)) {
                throw new IOException("Expected an initial header-only WAL");
            }
        } else {
            publish(wal, Wal.HEADER);
        }
        Path manifest = dir.resolve("manifest-" + FIRST + ".txt");
        publish(manifest, (MAGIC + wal.getFileName() + "\n").getBytes(StandardCharsets.UTF_8));
        return new TableSet(manifest, wal, List.of());
    }

    TableSet afterFlush() throws IOException {
        Path dir = manifest.getParent();
        BigInteger max = BigInteger.ZERO;
        // An interrupted publication or failed cleanup can leave higher generations on disk.
        for (String name : names(dir)) {
            String stem = name.endsWith(".tmp") ? name.substring(0, name.length() - 4) : name;
            if (LOG.matcher(stem).matches() || MANIFEST.matcher(stem).matches() || TABLE.matcher(stem).matches()) {
                String digits = stem.substring(stem.indexOf('-') + 1, stem.indexOf('.'));
                max = max.max(new BigInteger(digits));
            }
        }
        String id = String.format(Locale.ROOT, "%019d", max.add(BigInteger.ONE));
        if (id.length() > 19) {
            throw new IOException("Generation space exhausted");
        }
        var next = new ArrayList<>(tables);
        next.addFirst(dir.resolve("sst-" + id + ".sst"));
        return new TableSet(dir.resolve("manifest-" + id + ".txt"), dir.resolve("wal-" + id + ".log"), List.copyOf(next));
    }

    void publishEmptyWal() throws IOException {
        publish(wal, Wal.HEADER);
    }

    void commit() throws IOException {
        var text = new StringBuilder(MAGIC).append(wal.getFileName()).append('\n');
        for (Path table : tables) {
            text.append(table.getFileName()).append('\n');
        }
        publish(manifest, text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> names(Path dir) throws IOException {
        // try-with-resources closes the directory stream even when iteration fails.
        try (var paths = Files.list(dir)) {
            return paths.map(path -> path.getFileName().toString()).toList();
        }
    }

    private static void publish(Path target, byte[] contents) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (var file = FileChannel.open(temp, CREATE, TRUNCATE_EXISTING, WRITE)) {
            ByteBuffer data = ByteBuffer.wrap(contents);
            while (data.hasRemaining()) {
                file.write(data);
            }
            file.force(true);
        }
        Files.move(temp, target, ATOMIC_MOVE);
    }

    void cleanOrphans() {
        Path dir = manifest.getParent();
        try {
            for (String name : names(dir)) {
                Path path = dir.resolve(name);
                String stem = name.endsWith(".tmp") ? name.substring(0, name.length() - 4) : name;
                if (path.equals(manifest) || path.equals(wal) || tables.contains(path)
                        || !(LOG.matcher(stem).matches() || MANIFEST.matcher(stem).matches()
                             || TABLE.matcher(stem).matches())) {
                    continue;
                }
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    warn(path, e);
                }
            }
        } catch (IOException e) {
            warn(dir, e);
        }
    }

    private static void warn(Path path, IOException cause) {
        System.getLogger("kvlite").log(System.Logger.Level.WARNING,
                "Orphan cleanup deferred for " + path, cause);
    }
}
