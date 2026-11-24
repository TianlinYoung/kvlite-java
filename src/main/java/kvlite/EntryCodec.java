// Translate a record body between strict UTF-8 bytes and an Entry.
package kvlite;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

final class EntryCodec {
    static final int HEADER = 9;
    static final int MAX_KEY = 64 * 1024;
    static final int MAX_VALUE = 1024 * 1024;
    static final int MAX_BODY = HEADER + MAX_KEY + MAX_VALUE;

    // A ByteBuffer moves its cursor past each field using big-endian integers.
    static byte[] encode(Entry entry) {
        byte[] key = keyBytes(entry.key());
        boolean deleted = entry.value().isEmpty();
        byte[] value = deleted ? new byte[0] : bytes(entry.value().get(), MAX_VALUE);
        return ByteBuffer.allocate(HEADER + key.length + value.length)
                .put((byte) (deleted ? 2 : 1)).putInt(key.length)
                .putInt(deleted ? -1 : value.length).put(key).put(value).array();
    }

    static byte[] keyBytes(String key) {
        return bytes(key, MAX_KEY);
    }

    private static byte[] bytes(String text, int limit) {
        Objects.requireNonNull(text);
        // Every valid UTF-16 code unit needs at least one UTF-8 byte.
        if (text.length() > limit) {
            throw new IllegalArgumentException("String exceeds byte limit " + limit);
        }
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));
            if (encoded.remaining() > limit) {
                throw new IllegalArgumentException("String exceeds byte limit " + limit);
            }
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Unpaired surrogate", e);
        }
    }

    static Entry decode(byte[] body) throws IOException {
        if (body.length < HEADER) {
            throw new IOException("Short entry header");
        }
        ByteBuffer in = ByteBuffer.wrap(body);
        int kind = in.get();
        int keyLength = in.getInt();
        int valueLength = in.getInt();
        int expectedBytes = payloadLength(kind, keyLength, valueLength);
        if (in.remaining() != expectedBytes) {
            throw new IOException("Expected " + expectedBytes + " payload bytes, found " + in.remaining());
        }
        String key = readString(in, keyLength);
        return kind == 2 ? Entry.delete(key) : Entry.put(key, readString(in, valueLength));
    }

    static int payloadLength(int kind, int keyLength, int valueLength) throws IOException {
        boolean knownKind = kind == 1 || kind == 2;
        if (!knownKind) {
            throw new IOException("Unknown entry type: " + kind);
        }
        boolean validKeyLength = keyLength >= 0 && keyLength <= MAX_KEY;
        boolean validValueLength = kind == 2 ? valueLength == -1 : valueLength >= 0 && valueLength <= MAX_VALUE;
        if (!validKeyLength || !validValueLength) {
            throw new IOException("Invalid lengths for type " + kind + ": key=" + keyLength + ", value=" + valueLength);
        }
        return keyLength + Math.max(0, valueLength);
    }

    private static String readString(ByteBuffer in, int length) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().decode(in.slice(in.position(), length)).toString();
        in.position(in.position() + length);
        return text;
    }
}
