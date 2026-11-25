# kvlite-java interview notes

## 30-second introduction

kvlite-java is a Java 21 embedded key-value store for strings. It syncs each update to a write-ahead log before updating a sorted memtable. Flush publishes immutable disk tables, and a manifest selects the state to recover. Point reads use sparse indexes. Range scans and compaction share a streaming merge. A single read-write lock coordinates threads within one instance.

## Two-minute introduction

The project follows the write path of an LSM tree with a small file format that I can trace byte by byte. A put or delete validates the input, appends a length-checked and CRC-protected WAL frame, and calls `force(true)` before changing the memtable. A delete is a tombstone so a newer delete can hide a value in an older table. Either the memtable estimate or the WAL size can trigger a flush, which keeps repeated updates to one key from growing the log indefinitely.

Flush writes a sorted SSTable and a new WAL. An atomic rename publishes the manifest only after both files are ready. On reopen, the newest manifest determines which files to use. Recovery replays the active log and repairs a physically incomplete final frame. An intact frame with a bad checksum is reported as corruption.

Point lookup checks memory first and uses each table's sparse index to limit its search. A priority queue merges sorted sources for range scans and compaction. The newest record wins a key tie. Compaction reads all disk tables, so it can drop winning tombstones after consuming every older on-disk version. The active WAL still protects unflushed changes. A single instance allows concurrent readers, while maintenance runs under the write lock. The trade-off is that compaction can block every other operation on the instance. Tests compare 5,000 seeded operations against a TreeMap and reopen directory fixtures representing both sides of the manifest commit.

## Recovery explanation

Before the manifest rename, the previous manifest still points to the old WAL, which can rebuild the memtable. After the rename, the new manifest references a ready table and the new WAL. On startup, validate the published snapshot and replay the active WAL before cleaning orphan files. A short terminal frame is repairable. A complete frame with a bad CRC fails recovery. If a frame's length and its bitwise complement are corrupted consistently and the new valid length extends past EOF, recovery can mistake the corruption for an incomplete tail and truncate the WAL from that frame onward. The record at that position and any subsequent records can be lost. The recovery model covers process exits on a local filesystem with atomic rename support. The tests construct directory states from before and after the commit point and reopen them. They do not kill a running process.

中文要点：manifest 的原子发布决定读哪一代。先验证引用，再重放，最后清理。尾帧截断和完整帧 CRC 失败的处理不同。长度及反码一致损坏时可能误判为尾部截断，丢失该位置及之后的记录。提交点前后的用例通过构造目录状态验证重开恢复，没有杀进程测试。

## Compaction explanation

Put one head from each sorted disk source into a priority queue. Compare heads by key, then by the source's position in the newest-first manifest. For each key, retain the winner, advance its source, and drain and advance every remaining copy of that key. Stream surviving values into the writer. Dropping winning tombstones is safe here because every older disk version is consumed. Publish the replacement manifest, close old handles, and then remove retired files. The memtable and active WAL remain newest throughout.

中文要点：一个来源一个队首，同 key 先消费完所有版本。全量磁盘归并才能安全丢删除标记。写锁覆盖读输入、发布、切换和清理。

## Practice questions

1. **Why an LSM tree instead of a B-tree?** The memtable batches ordered updates into sequential table writes. A B-tree can offer fewer lookup hops but usually updates pages in place or via copy-on-write. This LSM still pays for per-record WAL sync and for merging.
2. **Where does write amplification come from?** A logical update is written to the WAL and eventually to an SSTable. Compaction can write the surviving value again. The store does not measure amplification in the throughput benchmark.
3. **Why sync the WAL before changing memory?** The log must be available to recover a successful update after process exit. If a write returns an I/O error, reopening may reveal a frame that reached disk before the error was reported.
4. **What is the cost of each `force(true)`?** Storage synchronization is in the timed put path and can dominate latency. Group commit could let multiple writes share one sync, then acknowledge those writes after the sync completes. The current write path holds the exclusive write lock through each write's sync, so it would need to be restructured to batch writes.
5. **How do you distinguish a torn tail from corruption?** Check the length and its complement first. A physically incomplete terminal frame can be truncated. A complete frame with an invalid CRC or an invalid record reports corruption. If the length and its complement are both corrupted but still match, a valid length that extends past EOF can be mistaken for a torn tail. Recovery may then discard the record at that position and every record after it.
6. **What marks the flush commit?** The final manifest filename appears through an atomic move. Before it, recovery uses the old WAL. After it, startup follows the new references. The project makes no cross-platform directory-entry power-loss guarantee.
7. **How does the sparse index affect lookups?** Find the floor sample and scan its block. Opening and keeping fewer index entries saves memory. The cursor normally reads a record header and payload separately, which adds a positional read and buffer work.
8. **Why is a tombstone different from a missing key?** Missing means search older sources. A tombstone is the latest visible result for that key, so it stops the search and hides any older value.
9. **How does the range merge avoid duplicates?** Order the queue by key and recency. Before returning the winner, consume the same key from every other source. Filter tombstones after selection. With N records and k sources the merge uses O(N log(k+1)) time and O(k) queue entries.
10. **When can compaction discard a tombstone?** When every older disk version for the key is included in the merge. This engine compacts all manifest tables and keeps the newer memtable above that result.
11. **What can run concurrently?** Multiple readers can proceed together with independent cursor positions. Writers, flush, compaction and close take the write lock. A scan builds its result under the read lock so later writes cannot change the returned list.
12. **How do the tests verify correctness?** The seeded model compares reads, scans, and complete mappings after flush, compact and reopen. Recovery fixtures construct and reopen directory states from before and after the manifest commit. The latch-controlled compaction test shows that `get` and `put` block while compaction pauses just after acquiring the write lock. It does not independently verify lock coverage for `scan` or `flush`, or detect a brief unlock after manifest publication and before the reader switch.

## Résumé bullets

AI coding agents wrote the project code. The bullets below describe project functionality.

- Java 21 embedded LSM-tree key-value store with per-write WAL synchronization, CRC-checked replay, sparse SSTable indexes, and atomic manifest publication.
- Streaming range scans and full SSTable compaction with newest-version deduplication, tombstone handling, and same-instance read/write synchronization.
For personal contributions, list only work you actually did and can explain. Claim independent implementation only if you did it yourself. Leave out contributions you cannot substantiate.
