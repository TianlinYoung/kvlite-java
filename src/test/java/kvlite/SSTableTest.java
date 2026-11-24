// Pin the byte layout, sparse block boundaries, and rejection of malformed tables.
package kvlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

class SSTableTest {
    @TempDir Path dir;

    @Test void emptyTableHasOnlyHeaderAndFooter() throws IOException {
        Path path = dir.resolve("empty");
        SSTableWriter.write(path, List.of());
        ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(path));
        assertEquals(32, bytes.remaining());
        assertEquals(0x4b565354, bytes.getInt());
        assertEquals(1, bytes.getInt());
        assertEquals(8L, bytes.getLong());
        assertEquals(0, bytes.getInt());
        assertEquals(0L, bytes.getLong());
        assertEquals(0x4b564654, bytes.getInt());
        try (var reader = new FileSSTableReader(path)) {
            assertNull(reader.get(""));
        }
    }

    @Test void sparseIndexFindsHitsAndMissesOnBothSidesOfEverySample() throws IOException {
        var entries = new ArrayList<Entry>();
        for (int i = 0; i < 68; i++) {
            String key = String.format(Locale.ROOT, "%03d", 2 * i);
            entries.add(i == 32 ? Entry.delete(key) : Entry.put(key, "猫" + i));
        }
        // UTF-16 and UTF-8 order differ for these two keys.
        entries.add(Entry.put("😀", ""));
        entries.add(Entry.put("\ue000", "last"));
        Path path = dir.resolve("table");
        SSTableWriter.write(path, entries);
        byte[] bytes = Files.readAllBytes(path);
        ByteBuffer footer = ByteBuffer.wrap(bytes, bytes.length - 24, 24);
        int indexStart = Math.toIntExact(footer.getLong());
        assertEquals(3, footer.getInt());
        assertEquals(70, footer.getLong());
        ByteBuffer index = ByteBuffer.wrap(bytes);
        index.position(indexStart);
        long offset = 8;
        for (int i = 0; i < entries.size(); i++) {
            if (i % 32 == 0) {
                byte[] key = new byte[index.getInt()];
                index.get(key);
                assertArrayEquals(EntryCodec.keyBytes(entries.get(i).key()), key);
                assertEquals(offset, index.getLong());
            }
            offset += EntryCodec.encode(entries.get(i)).length;
        }
        assertEquals(indexStart, offset);
        try (var reader = new FileSSTableReader(path)) {
            assertNull(reader.get(""));
            for (Entry entry : entries) {
                assertEquals(entry, reader.get(entry.key()));
                assertNull(reader.get(entry.key() + "!"));
            }
        }
    }

    @Test void rejectsMalformedFooterIndexAndTruncatedFiles() throws IOException {
        Path path = dir.resolve("table");
        SSTableWriter.write(path, List.of(Entry.put("a", "v")));
        byte[] good = Files.readAllBytes(path);
        int footer = good.length - 24;
        int index = (int) ByteBuffer.wrap(good).getLong(footer);
        for (int offset : new int[]{0, 7, footer, footer + 8, footer + 12, footer + 23,
                index, index + 4, index + 5}) {
            byte[] damaged = good.clone();
            damaged[offset] ^= 1;
            Files.write(path, damaged);
            assertThrows(IOException.class, () -> new FileSSTableReader(path), "offset " + offset);
        }
        Files.write(path, new byte[7]);
        assertThrows(IOException.class, () -> new FileSSTableReader(path));
    }

    @Test void duplicateKeysCannotPublishAnImmutableTable() {
        Path path = dir.resolve("duplicate");
        assertThrows(IOException.class, () -> SSTableWriter.write(path,
                List.of(Entry.put("a","v"), Entry.delete("a"))));
        assertFalse(Files.exists(path));
    }
}
