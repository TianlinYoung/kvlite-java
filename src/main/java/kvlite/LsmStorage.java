// Coordinate WAL writes, atomic flushes and newest-first lookups for one store.
package kvlite;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;

public final class LsmStorage implements Storage {
    private static final long MEMTABLE_LIMIT = 4L * 1024 * 1024;
    private static final long WAL_LIMIT = 16L * 1024 * 1024;
    private Wal wal;
    private TableSet files;
    private Memtable table = new Memtable();
    private final List<SSTableReader> readers = new ArrayList<>();
    private boolean closed;

    public static LsmStorage open(Path directory) throws IOException {
        TableSet files = TableSet.open(directory.toAbsolutePath());
        var storage = new LsmStorage(files);
        try {
            for (Path path : files.tables()) {
                storage.readers.add(new FileSSTableReader(path));
            }
            storage.wal.replay(storage.table);
        } catch (IOException e) {
            storage.closeAfterFailure(e);
            throw e;
        }
        files.cleanOrphans();
        return storage;
    }

    private LsmStorage(TableSet files) throws IOException {
        this.files = files;
        this.wal = new Wal(files.wal());
    }

    @Override
    public void put(String key, String value) throws IOException {
        write(Entry.put(key, value));
    }

    @Override
    public void delete(String key) throws IOException {
        write(Entry.delete(key));
    }

    private void write(Entry entry) throws IOException {
        requireOpen();
        byte[] body = EntryCodec.encode(entry);
        try {
            wal.append(body);
            table.apply(entry);
            if (table.encodedBytes() >= MEMTABLE_LIMIT || wal.size() >= WAL_LIMIT) {
                flushTable();
            }
        } catch (IOException e) {
            // Reopen to resolve a partial append or an uncertain flush commit.
            closeAfterFailure(e);
            throw e;
        }
    }

    @Override
    public Optional<String> get(String key) throws IOException {
        requireOpen();
        EntryCodec.keyBytes(key);
        Entry entry = table.get(key);
        if (entry != null) {
            return entry.value();
        }
        for (SSTableReader reader : readers) {
            entry = reader.get(key);
            if (entry != null) {
                // A tombstone is a hit: an older value must stay hidden.
                return entry.value();
            }
        }
        return Optional.empty();
    }

    @Override
    public void flush() throws IOException {
        requireOpen();
        try {
            flushTable();
        } catch (IOException e) {
            closeAfterFailure(e);
            throw e;
        }
    }

    private void flushTable() throws IOException {
        if (table.isEmpty()) {
            return;
        }
        TableSet next = files.afterFlush();
        Path newest = next.tables().getFirst();

        // 1. Publish the table while the old WAL can still recover the memtable.
        SSTableWriter.write(newest, table.sortedEntries());

        // 2. Publish the empty WAL so the next manifest can reference an existing log.
        next.publishEmptyWal();

        // 3. Atomically publish the manifest. Recovery now selects the new table and WAL.
        next.commit();

        // 4. Switch the live state to the committed generation before accepting more writes.
        wal.close();
        wal = new Wal(next.wal());
        table = new Memtable();
        // Replay validates the header and positions the first append just after it.
        wal.replay(table);
        readers.addFirst(new FileSSTableReader(newest));
        files = next;

        // 5. Retire old files after the commit and after closing the old WAL handle.
        files.cleanOrphans();
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Storage is closed");
        }
    }

    private void closeAfterFailure(IOException failure) {
        try {
            close();
        } catch (IOException closeError) {
            failure.addSuppressed(closeError);
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        IOException failure = null;
        try {
            wal.close();
        } catch (IOException e) {
            failure = e;
        }
        for (SSTableReader reader : readers) {
            try {
                reader.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
