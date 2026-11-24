// Coordinate real engine calls to verify lock coverage and lifecycle behavior.
package kvlite;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyTest {
    private static final int TIMEOUT = 10;
    @TempDir Path dir;

    @Test void compactionHoldsWriteLockUntilTheNewManifestIsLive() throws Exception {
        var lock = new ObservedLock();
        try (Storage db = LsmStorage.open(dir, lock)) {
            db.put("key", "old");
            db.flush();
            db.put("key", "new");
            db.flush();
            TableSet before = TableSet.open(dir);
            var pause = lock.pauseNextWriter(() -> {
                TableSet committed = assertDoesNotThrow(() -> TableSet.open(dir));
                assertNotEquals(before.manifest(), committed.manifest());
                assertEquals(1, committed.tables().size());
            });
            ExecutorService workers = workers();
            try {
                Future<?> compaction = workers.submit(() -> { db.compact(); return null; });
                await(pause.acquired);
                Future<Optional<String>> read = workers.submit(() -> db.get("key"));
                Future<?> write = workers.submit(() -> { db.put("later", "value"); return null; });
                await(pause.readerBlocked);
                await(pause.writerBlocked);
                assertFalse(read.isDone());
                assertFalse(write.isDone());
                pause.resume.countDown();
                compaction.get(TIMEOUT, SECONDS);
                assertEquals(Optional.of("new"), read.get(TIMEOUT, SECONDS));
                write.get(TIMEOUT, SECONDS);
                assertEquals(Optional.of("value"), db.get("later"));
            } finally {
                pause.resume.countDown();
                stop(workers);
            }
        }
    }

    @Test void operationsQueuedDuringCloseSeeClosedStorage() throws Exception {
        var lock = new ObservedLock();
        try (Storage db = LsmStorage.open(dir, lock)) {
            db.put("key", "durable");
            var pause = lock.pauseNextWriter(() -> assertThrows(IllegalStateException.class, db::stats));
            ExecutorService workers = workers();
            try {
                Future<?> close = workers.submit(() -> { db.close(); return null; });
                await(pause.acquired);
                Future<?> read = workers.submit(() -> assertThrows(IllegalStateException.class, db::stats));
                Future<?> write = workers.submit(() -> assertThrows(IllegalStateException.class,
                        () -> db.put("after", "rejected")));
                await(pause.readerBlocked);
                await(pause.writerBlocked);
                assertFalse(read.isDone());
                assertFalse(write.isDone());
                pause.resume.countDown();
                close.get(TIMEOUT, SECONDS);
                read.get(TIMEOUT, SECONDS);
                write.get(TIMEOUT, SECONDS);
                assertThrows(IllegalStateException.class, () -> db.get("key"));
                assertThrows(IllegalStateException.class, () -> db.scan("", "z"));
                assertThrows(IllegalStateException.class, db::flush);
                assertThrows(IllegalStateException.class, db::compact);
                assertThrows(IllegalStateException.class, () -> db.delete("key"));
            } finally {
                pause.resume.countDown();
                stop(workers);
            }
        }
        try (Storage reopened = LsmStorage.open(dir)) {
            assertEquals(Optional.of("durable"), reopened.get("key"));
            assertEquals(Optional.empty(), reopened.get("after"));
        }
    }

    @Test void simultaneousReadersAndWritersPreserveValuesAndReleaseLocksOnInvalidInput() throws Exception {
        try (Storage db = LsmStorage.open(dir)) {
            db.put("anchor", "on disk");
            db.flush();
            assertThrows(IllegalArgumentException.class, () -> db.scan("z", "a"));
            assertThrows(IllegalArgumentException.class, () -> db.put("bad", "\ud800"));
            ExecutorService workers = workers();
            var ready = new CountDownLatch(4);
            var start = new CountDownLatch(1);
            try {
                var tasks = new ArrayList<Future<?>>();
                for (String prefix : List.of("left-", "right-")) {
                    tasks.add(workers.submit(() -> {
                        ready.countDown();
                        await(start);
                        for (int i = 0; i < 30; i++) {
                            db.put(prefix + i, "value");
                            if (i % 2 == 0) {
                                db.delete(prefix + i);
                            }
                        }
                        return null;
                    }));
                    tasks.add(workers.submit(() -> {
                        ready.countDown();
                        await(start);
                        for (int i = 0; i < 30; i++) {
                            assertEquals(Optional.of("on disk"), db.get("anchor"));
                            assertEquals(List.of(Map.entry("anchor", "on disk")), db.scan("a", "b"));
                            assertEquals(1, db.stats().sstables());
                        }
                        return null;
                    }));
                }
                await(ready);
                start.countDown();
                for (Future<?> task : tasks) {
                    task.get(TIMEOUT, SECONDS);
                }
                assertEquals(31, db.scan("", "z").size());
                assertEquals(Optional.of("value"), db.get("left-29"));
                assertEquals(Optional.empty(), db.get("right-28"));
            } finally {
                start.countDown();
                stop(workers);
            }
        }
    }

    @Test void writeIoFailureClosesStoreAndReleasesTheLockForOtherThreads() throws Exception {
        try (Storage db = LsmStorage.open(dir)) {
            db.put("key", "durable");
            ExecutorService workers = workers();
            try {
                Future<?> failed = workers.submit(() -> {
                    // FileChannel closes and throws when used by an already-interrupted thread.
                    Thread.currentThread().interrupt();
                    try {
                        assertThrows(IOException.class, () -> db.put("interrupted", "write"));
                    } finally {
                        Thread.interrupted();
                    }
                });
                failed.get(TIMEOUT, SECONDS);
                workers.submit(() -> assertThrows(IllegalStateException.class, () -> db.get("key")))
                        .get(TIMEOUT, SECONDS);
                workers.submit(() -> assertThrows(IllegalStateException.class, db::flush))
                        .get(TIMEOUT, SECONDS);
            } finally {
                stop(workers);
            }
        }
        try (Storage reopened = LsmStorage.open(dir)) {
            assertEquals(Optional.of("durable"), reopened.get("key"));
        }
    }

    private static ExecutorService workers() {
        return Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon().factory());
    }

    private static void stop(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(TIMEOUT, SECONDS), "workers did not stop");
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(TIMEOUT, SECONDS), "timed out waiting for test synchronization");
    }

    private static final class Pause {
        final CountDownLatch acquired = new CountDownLatch(1);
        final CountDownLatch readerBlocked = new CountDownLatch(1);
        final CountDownLatch writerBlocked = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        final Runnable beforeUnlock;

        Pause(Runnable beforeUnlock) {
            this.beforeUnlock = beforeUnlock;
        }
    }

    private static final class ObservedLock extends ReentrantReadWriteLock {
        private final AtomicReference<Pause> next = new AtomicReference<>();
        private volatile Pause held;

        private final ReadLock read = new ReadLock(this) {
            @Override
            public void lock() {
                Pause pause = held;
                if (pause != null && !isWriteLockedByCurrentThread()) {
                    boolean acquired = tryLock();
                    if (acquired) {
                        super.unlock();
                    }
                    assertFalse(acquired, "reader acquired while the writer held the lock");
                    pause.readerBlocked.countDown();
                }
                super.lock();
            }
        };

        private final WriteLock write = new WriteLock(this) {
            @Override
            public void lock() {
                Pause pause = held;
                if (pause != null && !isWriteLockedByCurrentThread()) {
                    boolean acquired = tryLock();
                    if (acquired) {
                        super.unlock();
                    }
                    assertFalse(acquired, "second writer acquired while the first held the lock");
                    pause.writerBlocked.countDown();
                }
                super.lock();
                pause = next.getAndSet(null);
                if (pause != null) {
                    held = pause;
                    pause.acquired.countDown();
                    try {
                        await(pause.resume);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        held = null;
                        super.unlock();
                        throw new AssertionError(e);
                    } catch (AssertionError e) {
                        held = null;
                        super.unlock();
                        throw e;
                    }
                }
            }

            @Override
            public void unlock() {
                Pause pause = held;
                try {
                    if (pause != null) {
                        pause.beforeUnlock.run();
                    }
                } finally {
                    held = null;
                    super.unlock();
                }
            }
        };

        Pause pauseNextWriter(Runnable beforeUnlock) {
            Pause pause = new Pause(beforeUnlock);
            assertTrue(next.compareAndSet(null, pause));
            return pause;
        }

        @Override public ReadLock readLock() { return read; }
        @Override public WriteLock writeLock() { return write; }
    }
}
