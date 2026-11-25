// Read commands from a UTF-8 terminal and display storage results and errors.
package kvlite;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Repl {
    private static final String REOPEN = "Storage is closed. Restart the REPL with the same directory to reopen it.";

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java -cp <jar> kvlite.Repl <data-directory>");
            return;
        }
        // Closing the store on EOF releases file handles and leaves pending writes in the WAL.
        try (Storage store = LsmStorage.open(Path.of(args[0]))) {
            run(store, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("REPL error: " + e.getMessage());
        }
    }

    static void run(Storage store, BufferedReader input, PrintStream output) throws IOException {
        while (true) {
            output.print("kvlite> ");
            output.flush();
            String line = input.readLine();
            if (line == null) {
                output.println();
                return;
            }
            String command = "";
            try {
                List<String> tokens = tokenize(line);
                if (tokens.isEmpty()) {
                    continue;
                }
                command = tokens.getFirst();
                execute(store, tokens, output);
            } catch (IllegalArgumentException e) {
                output.println("Input error: " + e.getMessage());
            } catch (IllegalStateException e) {
                output.println(REOPEN);
                return;
            } catch (IOException e) {
                output.println("I/O error: " + e.getMessage());
                // These mutating operations close the store on I/O failure. Read errors leave it open.
                if (command.equals("put") || command.equals("delete") || command.equals("compact")) {
                    output.println(REOPEN);
                    return;
                }
            }
        }
    }

    private static List<String> tokenize(String line) {
        var tokens = new ArrayList<String>();
        int next = 0;
        while (next < line.length()) {
            if (Character.isWhitespace(line.charAt(next))) {
                next++;
                continue;
            }
            if (line.charAt(next) == '"') {
                next = readQuoted(line, next + 1, tokens);
                if (next < line.length() && !Character.isWhitespace(line.charAt(next))) {
                    throw new IllegalArgumentException("Separate arguments with whitespace");
                }
            } else {
                int start = next;
                while (next < line.length() && !Character.isWhitespace(line.charAt(next))) {
                    if (line.charAt(next) == '"') {
                        throw new IllegalArgumentException("A quote must start an argument");
                    }
                    next++;
                }
                tokens.add(line.substring(start, next));
            }
        }
        return tokens;
    }

    private static int readQuoted(String line, int next, List<String> tokens) {
        var token = new StringBuilder();
        while (next < line.length()) {
            char c = line.charAt(next++);
            if (c == '"') {
                tokens.add(token.toString());
                return next;
            }
            if (c == '\\' && next < line.length()) {
                c = line.charAt(next++);
                if (c != '"' && c != '\\') {
                    throw new IllegalArgumentException("Only quotes and backslashes can be escaped");
                }
            }
            token.append(c);
        }
        throw new IllegalArgumentException("Unclosed quoted argument");
    }

    private static void execute(Storage store, List<String> tokens, PrintStream output) throws IOException {
        switch (tokens.getFirst()) {
            case "put" -> {
                requireArgs(tokens, 3, "put <key> <value>");
                store.put(tokens.get(1), tokens.get(2));
                output.println("OK");
            }
            case "get" -> {
                requireArgs(tokens, 2, "get <key>");
                output.println(store.get(tokens.get(1)).map(Repl::quote).orElse("(missing)"));
            }
            case "delete" -> {
                requireArgs(tokens, 2, "delete <key>");
                store.delete(tokens.get(1));
                output.println("OK");
            }
            case "scan" -> {
                requireArgs(tokens, 3, "scan <from-inclusive> <to-exclusive>");
                var entries = store.scan(tokens.get(1), tokens.get(2));
                for (var entry : entries) {
                    output.println(quote(entry.getKey()) + " " + quote(entry.getValue()));
                }
                output.println(entries.size() + " result(s)");
            }
            case "stats" -> {
                requireArgs(tokens, 1, "stats");
                Stats stats = store.stats();
                output.printf("sstables=%d memtableBytes=%d walBytes=%d%n",
                        stats.sstables(), stats.memtableBytes(), stats.walBytes());
            }
            case "compact" -> {
                requireArgs(tokens, 1, "compact");
                store.compact();
                output.println("OK");
            }
            default -> throw new IllegalArgumentException("Unknown command. Use put, get, delete, scan, stats or compact.");
        }
    }

    private static void requireArgs(List<String> tokens, int size, String usage) {
        if (tokens.size() != size) {
            throw new IllegalArgumentException("Usage: " + usage);
        }
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
