// Append durable WAL frames and replay the valid prefix on open.
package kvlite;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.zip.CRC32;

final class Wal implements Closeable {
    static final byte[] HEADER = {'K', 'V', 'W', 'L', 0, 0, 0, 1};
    private final FileChannel file;

    Wal(Path path) throws IOException {
        file = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    void replay(Memtable table) throws IOException {
        if (file.size() < HEADER.length || !Arrays.equals(read(HEADER.length), HEADER)) {
            throw new IOException("Invalid WAL header");
        }
        long end = file.size();
        while (file.position() < end) {
            long start = file.position();
            if (end - start < 8) {
                discardTail(start);
                return;
            }
            byte[] prefix = read(8);
            ByteBuffer lengths = ByteBuffer.wrap(prefix);
            int length = lengths.getInt();
            if (lengths.getInt() != ~length || length < EntryCodec.HEADER || length > EntryCodec.MAX_BODY) {
                throw new IOException("Invalid WAL length at " + start);
            }
            if (end - file.position() < length + 4L) {
                discardTail(start);
                return;
            }
            byte[] body = read(length);
            if (ByteBuffer.wrap(read(4)).getInt() != checksum(prefix, body)) {
                throw new IOException("CRC mismatch at " + start);
            }
            try {
                table.apply(EntryCodec.decode(body));
            } catch (IOException e) {
                throw new IOException("Invalid WAL record at " + start, e);
            }
        }
    }

    void append(byte[] body) throws IOException {
        byte[] prefix = ByteBuffer.allocate(8).putInt(body.length).putInt(~body.length).array();
        ByteBuffer frame = ByteBuffer.allocate(12 + body.length)
                .put(prefix).put(body).putInt(checksum(prefix, body)).flip();
        while (frame.hasRemaining()) {
            file.write(frame);
        }
        // fsync: wait for the OS to persist the frame before exposing the new value.
        file.force(true);
    }

    private byte[] read(int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            if (file.read(buffer) < 0) {
                throw new IOException("Unexpected end of WAL");
            }
        }
        return buffer.array();
    }

    private void discardTail(long start) throws IOException {
        file.truncate(start);
        file.force(true);
    }

    private static int checksum(byte[] prefix, byte[] body) {
        CRC32 crc = new CRC32();
        crc.update(prefix);
        crc.update(body);
        return (int) crc.getValue();
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
