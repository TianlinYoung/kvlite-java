// Publish sorted immutable records followed by their sparse index and fixed footer.
package kvlite;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardOpenOption.*;

final class SSTableWriter {
    static final int MAGIC = 0x4b565354; // KVST
    static final int FOOTER_MAGIC = 0x4b564654; // KVFT
    static final int HEADER_BYTES = 8;
    static final int FOOTER_BYTES = 24;
    static final int STRIDE = 32;

    static void write(Path target, Iterable<Entry> records) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (var file = FileChannel.open(temp, CREATE_NEW, WRITE)) {
            var out = new DataOutputStream(Channels.newOutputStream(file));
            var indexBytes = new ByteArrayOutputStream();
            var index = new DataOutputStream(indexBytes);
            out.writeInt(MAGIC);
            out.writeInt(1);
            long count = 0;
            int samples = 0;
            String previous = null;
            for (Entry entry : records) {
                if (previous != null && previous.compareTo(entry.key()) >= 0) {
                    throw new IOException("SSTable keys must be unique and increasing");
                }
                if (count % STRIDE == 0) {
                    byte[] key = EntryCodec.keyBytes(entry.key());
                    index.writeInt(key.length);
                    index.write(key);
                    index.writeLong(file.position());
                    samples++;
                }
                out.write(EntryCodec.encode(entry));
                previous = entry.key();
                count++;
            }
            long indexStart = file.position();
            indexBytes.writeTo(out);
            out.writeLong(indexStart);
            out.writeInt(samples);
            out.writeLong(count);
            out.writeInt(FOOTER_MAGIC);
            file.force(true);
        }
        Files.move(temp, target, ATOMIC_MOVE);
    }
}
