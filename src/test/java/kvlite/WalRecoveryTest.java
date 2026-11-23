// Damage actual WAL files and check recovery through the normal open path.
package kvlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.zip.CRC32;
import static org.junit.jupiter.api.Assertions.*;

class WalRecoveryTest {
    @TempDir Path dir;

    @Test void everyIncompleteTailCanBeRepairedAndFollowedByAnotherWrite() throws IOException {
        Path wal = TableSet.open(dir).wal();
        int prefix;
        try (Storage db = LsmStorage.open(dir)) {
            db.put("a", "saved");
            prefix = Math.toIntExact(Files.size(wal));
            db.put("b", "猫");
        }
        byte[] complete = Files.readAllBytes(wal);
        for (int end = prefix; end < complete.length; end++) {
            Files.write(wal, Arrays.copyOf(complete, end));
            try (Storage db = LsmStorage.open(dir)) {
                assertEquals(prefix, Files.size(wal), "cut at " + end);
                assertEquals(Optional.of("saved"), db.get("a"));
                assertEquals(Optional.empty(), db.get("b"));
                db.put("b", "replacement");
            }
            try (Storage db = LsmStorage.open(dir)) {
                assertEquals(Optional.of("replacement"), db.get("b"));
            }
        }
    }

    @Test void badMiddleOrFinalCrcAndBadLengthWordsPreserveEvidence() throws IOException {
        Path wal = TableSet.open(dir).wal();
        int second;
        try (Storage db = LsmStorage.open(dir)) {
            db.put("a", "1");
            second = Math.toIntExact(Files.size(wal));
            db.delete("b");
        }
        byte[] valid = Files.readAllBytes(wal);
        for (int offset : new int[]{second - 1, valid.length - 1, second, second + 4, 0, 7}) {
            byte[] damaged = valid.clone();
            damaged[offset] ^= 1;
            assertRejected(wal, damaged);
        }
        for (int badLength : new int[]{8, Integer.MAX_VALUE, -1}) {
            byte[] damaged = valid.clone();
            ByteBuffer.wrap(damaged).putInt(second, badLength).putInt(second + 4, ~badLength);
            assertRejected(wal, damaged);
        }
        assertRejected(wal, Arrays.copyOf(valid, 7));
    }

    @Test void knownLimitationMatchingInflatedLengthsLookLikeAnIncompleteTail() throws IOException {
        Path wal = TableSet.open(dir).wal();
        int lastFrame;
        try (Storage db = LsmStorage.open(dir)) {
            db.put("k", "old");
            lastFrame = Math.toIntExact(Files.size(wal));
            db.put("k", "new");
        }
        byte[] bytes = Files.readAllBytes(wal);
        ByteBuffer frame = ByteBuffer.wrap(bytes);
        int inflated = frame.getInt(lastFrame) + 1;
        frame.putInt(lastFrame, inflated).putInt(lastFrame + Integer.BYTES, ~inflated);
        Files.write(wal, bytes);

        try (Storage db = LsmStorage.open(dir)) {
            assertEquals(Optional.of("old"), db.get("k"));
            assertArrayEquals(Arrays.copyOf(bytes, lastFrame), Files.readAllBytes(wal));
        }
    }

    @Test void validCrcDoesNotExcuseInvalidEncoding() throws IOException {
        Path wal = TableSet.open(dir).wal();
        try (Storage db = LsmStorage.open(dir)) {
            db.put("k", "v");
        }
        byte[] frame = Files.readAllBytes(wal);
        frame[16] = 7;
        CRC32 crc = new CRC32();
        crc.update(frame, 8, frame.length - 12);
        ByteBuffer.wrap(frame).putInt(frame.length - 4, (int) crc.getValue());
        assertRejected(wal, frame);
    }

    private void assertRejected(Path wal, byte[] damaged) throws IOException {
        Files.write(wal, damaged);
        assertThrows(IOException.class, () -> LsmStorage.open(dir));
        assertArrayEquals(damaged, Files.readAllBytes(wal));
    }
}
