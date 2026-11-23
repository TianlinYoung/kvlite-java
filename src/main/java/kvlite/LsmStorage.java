// Coordinate the WAL and memtable for a single owner of a data directory.
package kvlite;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public final class LsmStorage implements Storage {
    private final Wal wal;
    private final Memtable table = new Memtable();
    private boolean closed;

    public static LsmStorage open(Path directory) throws IOException {
        TableSet files = TableSet.open(directory.toAbsolutePath());
        var storage = new LsmStorage(new Wal(files.wal()));
        try {
            storage.wal.replay(storage.table);
        } catch (IOException e) {
            storage.closeAfterFailure(e);
            throw e;
        }
        files.cleanOrphans();
        return storage;
    }

    private LsmStorage(Wal wal) {
        this.wal = wal;
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
        } catch (IOException e) {
            // A partial append makes subsequent writes unsafe until replay.
            closeAfterFailure(e);
            throw e;
        }
        table.apply(entry);
    }

    @Override
    public Optional<String> get(String key) {
        requireOpen();
        EntryCodec.keyBytes(key);
        return table.get(key);
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
        wal.close();
    }
}
