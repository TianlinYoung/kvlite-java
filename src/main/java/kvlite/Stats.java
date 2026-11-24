// Snapshot of the active tables, estimated live memtable bytes and WAL size.
package kvlite;

public record Stats(int sstables, long memtableBytes, long walBytes) {}
