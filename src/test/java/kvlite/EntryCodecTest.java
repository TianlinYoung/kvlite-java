// Check byte-level compatibility and rejection of invalid text and lengths.
package kvlite;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class EntryCodecTest {
    @Test void exactBodyBytesDistinguishEmptyFromDeleted() throws IOException {
        byte[] value = {1, 0,0,0,1, 0,0,0,0, 'k'};
        byte[] deletion = {2, 0,0,0,1, -1,-1,-1,-1, 'k'};
        assertArrayEquals(value, EntryCodec.encode(Entry.put("k", "")));
        assertEquals(Entry.put("k", ""), EntryCodec.decode(value));
        assertArrayEquals(deletion, EntryCodec.encode(Entry.delete("k")));
        assertEquals(Entry.delete("k"), EntryCodec.decode(deletion));
    }

    @Test void limitsUseEncodedBytes() throws IOException {
        var largest = Entry.put("x".repeat(65536), "y".repeat(1048576));
        assertEquals(largest, EntryCodec.decode(EntryCodec.encode(largest)));
        assertThrows(IllegalArgumentException.class, () -> EntryCodec.keyBytes("é".repeat(32769)));
        assertThrows(IllegalArgumentException.class, () -> EntryCodec.encode(Entry.put("k","a".repeat(1048577))));
        assertThrows(IllegalArgumentException.class, () -> EntryCodec.keyBytes("\uD800"));
    }

    @Test void invalidBodiesAreRejected() {
        byte[] body = EntryCodec.encode(Entry.put("k","v"));
        for (int size = 0; size < body.length; size++) {
            byte[] shortBody = Arrays.copyOf(body, size);
            assertThrows(IOException.class, () -> EntryCodec.decode(shortBody));
        }
        body[0] = 3;
        assertThrows(IOException.class, () -> EntryCodec.decode(body));
        body[0] = 2;
        assertThrows(IOException.class, () -> EntryCodec.decode(body));
        body[0] = 1;
        body[9] = (byte) 0xff;
        assertThrows(IOException.class, () -> EntryCodec.decode(body));
        ByteBuffer.wrap(body).putInt(1, Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> EntryCodec.decode(body));
    }
}
