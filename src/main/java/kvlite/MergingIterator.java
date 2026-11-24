// Merge sorted sources and yield the newest physical record for each key.
package kvlite;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

final class MergingIterator implements EntrySource {
    private record Head(EntrySource source, int rank, Entry entry) {
        String key() {
            return entry.key();
        }
    }

    // A lower rank represents a newer source and wins ties for the same key.
    private final PriorityQueue<Head> heads = new PriorityQueue<>(
            Comparator.comparing(Head::key).thenComparingInt(Head::rank));

    MergingIterator(List<EntrySource> newestFirst) throws IOException {
        int rank = 0;
        for (EntrySource source : newestFirst) {
            advance(source, rank++);
        }
    }

    @Override
    public Entry next() throws IOException {
        Head winner = heads.poll();
        if (winner == null) {
            return null;
        }
        advance(winner.source(), winner.rank());
        // Drain all older copies before returning so they cannot reappear on the next call.
        while (!heads.isEmpty() && heads.element().key().equals(winner.key())) {
            Head older = heads.remove();
            advance(older.source(), older.rank());
        }
        return winner.entry();
    }

    Entry nextValue() throws IOException {
        for (Entry winner = next(); winner != null; winner = next()) {
            if (winner.value().isPresent()) {
                return winner;
            }
        }
        return null;
    }

    private void advance(EntrySource source, int rank) throws IOException {
        Entry entry = source.next();
        if (entry != null) {
            heads.add(new Head(source, rank, entry));
        }
    }
}
