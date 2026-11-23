// Bound positional reads to a single section of an immutable file.
package kvlite;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

final class TableCursor {
    private final FileChannel file;
    private final long end;
    private long position;

    TableCursor(FileChannel file, long start, long end) {
        this.file = file;
        this.position = start;
        this.end = end;
    }

    long remaining() {
        return end - position;
    }

    ByteBuffer read(int length) throws IOException {
        if (length < 0 || length > remaining()) {
            throw new IOException("SSTable field exceeds its section at " + position);
        }
        ByteBuffer bytes = ByteBuffer.allocate(length);
        while (bytes.hasRemaining()) {
            int n = file.read(bytes, position);
            if (n < 0) {
                throw new IOException("Truncated SSTable at " + position);
            }
            position += n;
        }
        return bytes.flip();
    }

    Entry readEntry() throws IOException {
        ByteBuffer header = read(EntryCodec.HEADER);
        int payload = EntryCodec.payloadLength(header.get(), header.getInt(), header.getInt());
        ByteBuffer body = ByteBuffer.allocate(EntryCodec.HEADER + payload);
        body.put(header.rewind()).put(read(payload));
        return EntryCodec.decode(body.array());
    }
}
