// Load a validated sparse index and read only the block containing a requested key.
package kvlite;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.TreeMap;

import static kvlite.SSTableWriter.*;
import static java.nio.file.StandardOpenOption.READ;

final class FileSSTableReader implements SSTableReader {
    private final FileChannel file;
    private final TreeMap<String, Long> index = new TreeMap<>();
    private final long dataEnd;

    FileSSTableReader(Path path) throws IOException {
        file = FileChannel.open(path, READ);
        try {
            dataEnd = loadIndex();
        } catch (IOException e) {
            file.close();
            throw e;
        }
    }

    private long loadIndex() throws IOException {
        long footerStart = file.size() - FOOTER_BYTES;
        var header = new TableCursor(file, 0, footerStart).read(HEADER_BYTES);
        if (header.getInt() != MAGIC || header.getInt() != 1) {
            throw new IOException("Invalid SSTable header");
        }
        var footer = new TableCursor(file, footerStart, file.size()).read(FOOTER_BYTES);
        long indexStart = footer.getLong();
        int samples = footer.getInt();
        long count = footer.getLong();
        if (footer.getInt() != FOOTER_MAGIC) {
            throw new IOException("Invalid SSTable footer magic");
        }
        if (indexStart < HEADER_BYTES || indexStart > footerStart) {
            throw new IOException("SSTable index start outside file bounds: " + indexStart);
        }
        long maxRecords = (indexStart - HEADER_BYTES) / EntryCodec.HEADER;
        if (count < 0 || count > maxRecords) {
            throw new IOException("SSTable record count " + count + " outside range [0, " + maxRecords + "]");
        }
        long expectedSamples = Math.ceilDiv(count, STRIDE);
        if (samples != expectedSamples) {
            throw new IOException("Expected " + expectedSamples + " SSTable index samples, found " + samples);
        }
        if (count == 0 && indexStart != HEADER_BYTES) {
            throw new IOException("Empty SSTable must have an empty data section");
        }
        var cursor = new TableCursor(file, indexStart, footerStart);
        long previous = -1;
        for (int i = 0; i < samples; i++) {
            int length = cursor.read(Integer.BYTES).getInt();
            if (length < 0 || length > EntryCodec.MAX_KEY) {
                throw new IOException("Invalid index key length");
            }
            String key = StandardCharsets.UTF_8.newDecoder().decode(cursor.read(length)).toString();
            long offset = cursor.read(Long.BYTES).getLong();
            if (i == 0 && offset != HEADER_BYTES) {
                throw new IOException("First SSTable index offset must be " + HEADER_BYTES + ", found " + offset);
            }
            if (offset <= previous) {
                throw new IOException("SSTable index offsets must increase at sample " + i);
            }
            if (offset > indexStart - EntryCodec.HEADER) {
                throw new IOException("SSTable index offset leaves no room for a record header: " + offset);
            }
            if (i > 0 && key.compareTo(index.lastKey()) <= 0) {
                throw new IOException("SSTable index keys must increase at sample " + i);
            }
            var anchor = new TableCursor(file, offset, indexStart);
            var recordHeader = anchor.read(EntryCodec.HEADER);
            int kind = recordHeader.get();
            int keyLength = recordHeader.getInt();
            int payload = EntryCodec.payloadLength(kind, keyLength, recordHeader.getInt());
            if (payload > anchor.remaining() || !key.equals(StandardCharsets.UTF_8.newDecoder()
                    .decode(anchor.read(keyLength)).toString())) {
                throw new IOException("Index key differs from sampled record");
            }
            index.put(key, offset);
            previous = offset;
        }
        if (cursor.remaining() != 0) {
            throw new IOException("Unexpected bytes after sparse index");
        }
        return indexStart;
    }

    public Entry get(String key) throws IOException {
        var sample = index.floorEntry(key);
        if (sample == null) {
            return null;
        }
        var next = index.higherEntry(sample.getKey());
        var cursor = new TableCursor(file, sample.getValue(), next == null ? dataEnd : next.getValue());
        while (cursor.remaining() > 0) {
            Entry entry = cursor.readEntry();
            int order = entry.key().compareTo(key);
            if (order == 0) {
                return entry;
            }
            if (order > 0) {
                break;
            }
        }
        return null;
    }

    @Override
    public EntrySource entries() {
        var cursor = new TableCursor(file, HEADER_BYTES, dataEnd);
        return () -> cursor.remaining() > 0 ? cursor.readEntry() : null;
    }

    @Override
    public EntrySource range(String fromInclusive, String toExclusive) {
        var sample = index.floorEntry(fromInclusive);
        // Below the first sample, begin at the first record. The upper sample bounds disk reads.
        long start = sample == null ? HEADER_BYTES : sample.getValue();
        var upperSample = index.ceilingEntry(toExclusive);
        long end = upperSample == null ? dataEnd : upperSample.getValue();
        var cursor = new TableCursor(file, start, end);
        return new EntrySource() {
            private boolean exhausted;

            @Override
            public Entry next() throws IOException {
                while (!exhausted && cursor.remaining() > 0) {
                    Entry entry = cursor.readEntry();
                    if (entry.key().compareTo(toExclusive) >= 0) {
                        exhausted = true;
                    } else if (entry.key().compareTo(fromInclusive) >= 0) {
                        return entry;
                    }
                }
                return null;
            }
        };
    }

    public void close() throws IOException {
        file.close();
    }
}
