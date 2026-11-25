// Drive the shell with scripted input against real stores.
package kvlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

class ReplTest {
    @TempDir Path dir;

    @Test void commandsHandleQuotedValuesEmptyStringsAndMissingKeys() throws IOException {
        try (Storage db = LsmStorage.open(dir)) {
            String output = run(db, """
                    stats
                    put "" ""
                    put "a b" "say \\"hi\\" \\\\"
                    get "a b"
                    scan "" z
                    delete "a b"
                    get "a b"
                    get ""
                    compact
                    """);
            assertTrue(output.contains("sstables=0 memtableBytes=0 walBytes=8"));
            assertTrue(output.contains("\"say \\\"hi\\\" \\\\\""));
            assertTrue(output.contains("\"a b\" \"say \\\"hi\\\" \\\\\""));
            assertTrue(output.contains("2 result(s)"));
            assertTrue(output.contains("(missing)"));
            assertEquals(Optional.of(""), db.get(""));
            assertEquals(Optional.empty(), db.get("a b"));
        }
    }

    @Test void malformedCommandsGetUsagesOrErrorsAndLeaveTheShellOpen() throws IOException {
        try (Storage db = LsmStorage.open(dir)) {
            String output = run(db, """

                    unknown
                    put only-key
                    put "unclosed
                    put "quotes"adjacent value
                    put word"quote value
                    put key "bad\\q"
                    scan z a
                    get extra argument
                    stats extra
                    delete
                    compact extra
                    put ok final
                    get ok
                    """);
            for (String error : new String[]{"Unknown command", "Usage: put", "Unclosed quoted argument",
                    "Separate arguments", "A quote must start", "Only quotes and backslashes",
                    "Scan lower bound exceeds upper bound", "Usage: get", "Usage: stats",
                    "Usage: delete", "Usage: compact"}) {
                assertTrue(output.contains("Input error: " + error), error);
            }
            assertTrue(output.contains("\"final\""));
        }
    }

    @Test void readErrorLeavesStoreOpenButCompactionErrorRequiresRestart() throws IOException {
        Entry first = Entry.put("a", "good");
        try (Storage db = LsmStorage.open(dir)) {
            db.put("a", "good");
            db.put("b", "bad");
            db.flush();
        }
        Path sst = TableSet.open(dir).tables().getFirst();
        byte[] bytes = Files.readAllBytes(sst);
        bytes[SSTableWriter.HEADER_BYTES + EntryCodec.encode(first).length] = 0;
        Files.write(sst, bytes);
        try (Storage db = LsmStorage.open(dir)) {
            String output = run(db, "get b\nstats\ncompact\nput after rejected\n");
            assertEquals(2, output.lines().filter(line -> line.contains("I/O error:")).count());
            assertTrue(output.contains("sstables=1 memtableBytes=0 walBytes=8"));
            assertTrue(output.contains("Restart the REPL with the same directory"));
            assertFalse(output.contains("OK"));
            assertThrows(IllegalStateException.class, db::stats);
        }
    }

    @Test void alreadyClosedStoreIsReported() throws IOException {
        Storage db = LsmStorage.open(dir);
        db.close();
        assertTrue(run(db, "stats\n").contains("Restart the REPL with the same directory"));
    }

    private static String run(Storage db, String input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new PrintStream(bytes, false, UTF_8)) {
            Repl.run(db, new BufferedReader(new StringReader(input)), output);
        }
        return bytes.toString(UTF_8);
    }
}
