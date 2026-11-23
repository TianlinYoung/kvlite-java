# kvlite-java 阶段 0 设计方案

本文定义一个可在 SWE 实习面试中逐步讲清楚的 Java 21 LSM-tree 键值存储，以及各阶段的验收条件。

## 1. 对外行为

- `Storage` 是调用者使用的接口。`put(key, value)` 写入或覆盖，`get(key)` 返回 `Optional<String>`，`delete(key)` 写入 tombstone（删除标记）。即使 key 当前不存在，删除也走相同写路径。内部查找必须区分「缺失」「有值」「已删除」，遇到 tombstone 就停止查找更旧的表。
- 接受空字符串，拒绝 `null` 和含有不成对 surrogate 的 Java 字符串。持久化使用严格 UTF-8，key 和 value 的编码上限草案分别为 64 KiB 和 1 MiB。空 value 与删除由记录类型区分。全局按 `String.compareTo` 比较 key，磁盘索引查找也比较解码后的 Java 字符串。
- `scan(fromInclusive, toExclusive)` 返回独立的、有序的键值列表。相等端点得到空结果，反向端点报参数错误。`flush()`、`compact()` 和 `stats()` 作为维护操作由引擎提供，`close()` 释放文件资源并允许从 WAL 恢复剩余写入。
- 一个数据目录由一个引擎实例独占。阶段 1–3 要求单线程调用。阶段 4 支持同一实例多线程调用。多个进程或实例同时打开同一目录属于已知限制。

## 2. 目标目录

```text
kvlite-java/
├── docs/
│   ├── PLAN.md                 阶段 0
│   ├── ARCHITECTURE.md         阶段 4
│   ├── FILE_FORMAT.md
│   ├── DECISIONS.md
│   ├── INTERVIEW_NOTES.md
│   └── READING_GUIDE.md
├── pom.xml                     阶段 1
├── mvnw, mvnw.cmd
├── .mvn/wrapper/
├── .github/workflows/verify.yml
├── src/main/java/kvlite/
│   ├── Storage, LsmStorage, Memtable, Entry, EntryCodec, Wal
│   ├── TableSet, SSTableReader, FileSSTableReader, SSTableWriter
│   ├── MergingIterator         阶段 3
│   ├── Repl                    阶段 4
│   └── Benchmark               阶段 4
├── src/test/java/kvlite/
└── README.md                   阶段 4
```

| 类或接口 | 职责 |
| --- | --- |
| `Storage` / `LsmStorage` | `Storage` 定义数据操作和关闭行为。`LsmStorage` 实现接口，负责协调日志、内存表、磁盘表和维护操作。 |
| `Entry` / `EntryCodec` | 表示一条值或 tombstone，并集中处理二进制编码、严格 UTF-8 和长度校验。 |
| `Memtable` | 用 `TreeMap<String, Entry>` 保存每个 key 最新的记录和有序视图。 |
| `Wal` | 追加、同步和重放记录，报告损坏的位置，必要时截去不完整尾部。 |
| `TableSet` | 读取和提交 manifest 快照，管理当前 WAL 与 SSTable 文件集合。 |
| `SSTableReader` / `FileSSTableReader` | 隔离表的点查找及按范围顺序读。文件实现解析 footer 和稀疏索引，按需读取记录。 |
| `SSTableWriter` | 顺序写入已排序的唯一 key，记录索引偏移并生成 footer。 |
| `MergingIterator` | 用 `PriorityQueue` 归并多个有序来源，每个 key 只交付最新记录。scan 和 compaction 共用。 |
| `Repl` / `Benchmark` | REPL 解析命令和展示结果。基准程序独立执行可复现的测试负载。 |

每个源文件开头一句话说明职责。首次用到 `ByteBuffer`、fsync 或 try-with-resources 时，注释简述其用途。SSTable 共享只读 FileChannel，各游标维护自己的偏移，用 `FileChannel.read(ByteBuffer, position)` 定位读取并处理短读。`ByteBuffer` 保存本次读出的字节，定位读允许多个读线程各自向前推进。

## 3. 数据路径和恢复

### WAL 与内存表

1. 校验输入，构造 value 或 tombstone。
2. 循环写完 WAL 帧，调用 `FileChannel.force(true)` 请求持久化文件数据及元数据。
3. 更新 memtable，按当前各条记录的 key 和 value 的 UTF-8 字节数之和估算大小。覆盖时先减旧条目再加新条目，tombstone 只计 key。从阶段 2 起，估算达到 4 MiB 或活动 WAL 文件达到 16 MiB（默认值，含 header 与帧开销）时同步 flush。反复覆盖一个 key 也会触发 WAL 上限。两项均在一条写入完成后检查，所以最多越过阈值一条记录。
4. WAL 追加或同步失败时关闭写入路径并要求重新打开。调用者收到 I/O 错误时，重启后该操作可能已生效。自动 flush 失败也向调用者报告同样的结果不确定性，实例停止接收操作并允许关闭。

阶段 1 从 manifest 找到活动 WAL。启动时从 header 后顺序读完帧，按日志顺序重建 `TreeMap`。EOF 位于完整帧的末尾是正常结束。EOF 位于不足 8 字节的帧头中，或在通过帧头校验后出现在帧体或 CRC 内，允许截断至最近的完整帧位置并强制同步，然后才允许继续追加。完整帧的 CRC、非法长度或非法记录编码报错，保留原文件。保留从损坏处开始的字节有助于定位问题。成对损坏且恰好通过长度反码校验的长度字仍可能被当成截断，本格式无法可靠区分这种情况。

### Flush 与提交点

从阶段 1 起，启动选择编号最高的最终 manifest，校验格式、活动 WAL 和所有引用的表。缺失引用或校验失败即报错，保留现场。新建库先同步并发布初始 WAL，再通过同目录临时文件原子发布第一份 manifest（活动 WAL，空表列表），随后才接受写入。若没有最终 manifest，但已存在仅含合法 header 的初始 WAL，则沿用该文件，直接为它发布第一份 manifest。若只有建库临时文件，则创建并同步初始 WAL 后再发布 manifest。遇到含记录的 WAL 或最终 SSTable 则报错保留文件。

1. 将 memtable 按 key 顺序写入临时 SSTable。同步文件、关闭文件句柄，然后原子移动到新的最终文件名。创建并同步一个只有 header 的新 WAL。
2. 写入、同步并关闭新 manifest 的临时文件，再将其原子移动到新编号的最终文件名。这个移动是提交点。若文件系统不支持同目录原子移动，报错并停止发布。
3. 提交后切换内存中的表集合和 WAL 句柄，清空 memtable。旧 WAL 及旧 manifest 在新状态确认生效后才允许清理。关闭旧文件句柄后再尝试清理。清理失败可保留文件待重试，读路径只认当前快照。

提交前退出时，旧 manifest 仍引用旧 WAL，恢复会包含本次 memtable 的内容。提交后退出时，新 manifest 引用已发布的表和新 WAL。后续写入始终追加到新 WAL。启动完成当前快照及其引用文件的验证和 WAL 恢复后，删除符合引擎命名规则的临时文件、未被引用的 SSTable 和 WAL，以及更旧的 manifest。删除失败只记录文件名和原因，下次启动重试。分配编号时计入仍存在的文件。空 memtable 的 flush 直接返回。设计和测试以本地盘上的进程退出为范围。文件同步加原子改名未建立跨平台的目录项断电持久化保证。 [Java 21 `FileChannel.force`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean)) · [Java 21 `Files.move`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...))

### 点查找、范围与 compaction

- 点查找先读 memtable，再从新到旧查 SSTable。每张表对索引做 floor 查找，跳到不大于目标的最近采样 key，再顺序读取到下一个采样点或数据区末尾。目标在第一个采样 key 之前时可直接返回缺失。
- `MergingIterator` 为每个非空来源在优先队列中保留一条记录。按 key 升序、同 key 按来源从新到旧排序（memtable 最新，磁盘来源顺序取自 manifest）。取出队首作为胜者，消耗该 key 在所有来源中的记录，每消费一条就推进对应游标，再交付胜者。归并 N 条输入、k 个来源需 O(N log(k+1)) 时间，队列最多保留 k 条记录。
- scan 将各来源限制在 `[fromInclusive, toExclusive)`，下界早于首个索引项时从首条开始。对胜者过滤 tombstone，只有最终可见的键值对进入返回的 List。
- Compaction 对 manifest 的全部 SSTable 使用同一迭代器，把胜出的 value 逐条交给 `SSTableWriter`。胜出的 tombstone 可丢弃，因为这个 key 的全部旧磁盘版本已一并消费。活动 WAL 和 memtable 仍然最新。无输入时直接返回，全删时提交空表列表。沿用 manifest 提交点，发布成功后清理旧输入。归并内存主要是游标和队列，读写器还需要稀疏索引。阶段 4 的 compaction 全程持写锁，其他线程的读取和写入都要等待。

## 4. 文件格式草案

整数采用大端序。`u8`、`u32` 和 `u64` 表示固定宽度无符号整数，`i32` 允许 `-1`。`u64` 偏移仅使用 Java 非负 `long` 范围。字符串字段只写 UTF-8 字节，无终止字节。读端在分配内存和 seek 前校验长度与偏移。Magic 是固定 ASCII 字节，紧随的版本字段为 `u32 = 1`。

### 共享记录体

| 顺序 | 字段 | 宽度及含义 |
| --- | --- | --- |
| 1 | `kind` | `u8`，`0x01` 为 value，`0x02` 为 tombstone |
| 2 | `keyLength` | `u32`，key 编码字节数 |
| 3 | `valueLength` | `i32`，value 编码字节数。tombstone 固定为 `-1` |
| 4 | `key` | `keyLength` 字节 |
| 5 | `value` | value 记录为 `valueLength` 字节。tombstone 为 0 字节 |

### WAL

```text
[KVWL: 4 bytes][version: u32] [frame]...
frame = [bodyLength: u32][~bodyLength: u32][body][CRC32: u32]
```

`bodyLength` 为共享记录体的字节数，范围为 9 至 1,114,121。长度反码按 32 位逐位取反，用来检测帧头长度受损。使用 Java `CRC32`，输入依次为原始帧头 8 字节和记录体，结果存原始 32 位。先验证帧头和长度范围，再判断是否够读整个帧。若长度因损坏变大，读端可能误判为写到一半，长度反码让读端在检查 EOF 前发现单个长度字的损坏。完整帧还要核对 CRC、字段长度之和、类型和 UTF-8。如果初始 header 本身不完整或非法，打开失败。初始文件经临时文件写入、同步和原子移动后才成为可打开的 WAL。

### SSTable

```text
[KVST: 4 bytes][version: u32] [body]... [sparse index] [footer: 24 bytes]
index entry = [keyLength: u32][key][recordOffset: u64]
footer      = [indexOffset: u64][indexCount: u32][recordCount: u64][KVFT: 4 bytes]
```

数据区是 `[8, indexOffset)`，使用共享记录体。每 32 条记录索引一次，第一条必索引，索引项数量为记录数除以 32 向上取整。索引区从 `indexOffset` 开始，在 footer 前结束。索引项的 offset 指向对应记录的 `kind`，两个 offset 字段均从文件起点算起。读端从文件尾倒数 24 字节找 footer，校验 magic、计数关系、索引边界与顺序、各偏移的范围，以及所读记录的长度和类型。空表格式合法，两个计数均为 0，`indexOffset = 8`。SSTable 草案没有记录 CRC，结构校验无法检测所有位翻转。

### Manifest

UTF-8 文本，行尾为 LF。首行固定 `KVMANIFEST 1`，第二行是活动 WAL 文件名，余下各行各写一个 SSTable 文件名，顺序从新到旧。只允许约定前缀、19 位十进制正编号和后缀组成的文件名。拒绝重复的表和未知格式。示例文件名为 `manifest-0000000000000000001.txt`、`wal-0000000000000000001.log`、`sst-0000000000000000001.sst`。表的新旧顺序以 manifest 为准。

## 5. 阶段交付与验收

| 阶段 | 交付 | 验收 |
| --- | --- | --- |
| 0 | 仅 `docs/PLAN.md` | 核对目录、接口、字节布局、提交点、测试矩阵和阶段边界。提交方案供用户与 Claude 审阅。 |
| 1 | Maven、wrapper、CI、Memtable、WAL、最简 manifest 和引擎恢复路径 | 验证新建库中途退出可重试、首次写入前已有 manifest。JUnit 5 测覆盖、删除、空值、Unicode、关闭重开。对一个完整 WAL 的最后一帧逐字节截断，验证只恢复完整前缀、截尾后可继续写。篡改完整末帧和中间帧 CRC、篡改单个长度字，验证打开失败且文件不变。 |
| 2 | SSTable 读写、阈值与手动 flush、manifest、点读路径 | 覆盖稀疏索引两侧、空表、损坏 footer、较新 tombstone 遮挡旧值和跨表读。构造提交前后目录状态，验证重开、再次写入、再次 flush。分别触发 4 MiB 内存估算和反复覆盖引起的 WAL 上限。验证提交前崩溃留下的孤儿表在重开后被清理，旧 WAL 中的值仍可读。 |
| 3 | Compaction、range scan、模型对照 | 固定种子执行至少 5,000 步 `put/delete/get/flush/compact/scan`，加入重开。随机读与扫描逐次比对参考 `TreeMap`，每次 flush、compact 和重开后检查全量映射。失败输出 seed、步骤与操作。单测多来源同 key、空来源和 tombstone 胜出。另测全删、重复覆盖、提交前后重开与 memtable 尚未 flush 的 compaction。 |
| 4 | REPL、简单 Java benchmark、同实例并发、其余文档 | REPL 支持 `put/get/delete/scan/stats/compact`。使用一个 `ReadWriteLock`，读锁覆盖 get、scan、stats，写锁覆盖更新、flush、compact、close。scan 在锁内完成并返回结果副本。验证 compaction 持写锁时其他读者和写者均须等待。验证多线程写读和关闭行为，实跑并记录基准。 |

阶段 1 起，GitHub Actions 在 `push` 和 `pull_request` 时用 JDK 21 跑 `./mvnw verify`。Windows 本地对应 `.\mvnw.cmd verify`。Wrapper 固定 Maven 版本。JUnit 5 是测试依赖，JaCoCo、编译器和测试运行器属于构建插件，运行时只依赖 JDK。每个可执行组件有对应单元测试，JaCoCo 在 `verify` 检查主代码行覆盖率至少 85%，仅排除 `Benchmark.main`。将 Benchmark 类限制为只有 main 的入口，实际负载与统计放在单独受检查的类中，便于按类设置排除。以后每阶段 PR 都列出改动、阅读顺序、实际测试结果和假设。用户与 Claude 审阅确认后再进入下一阶段。 [Apache Maven Wrapper](https://maven.apache.org/tools/wrapper/)

## 6. 阶段 4 文档和基准清单

- `README.md` 包含一句话介绍、架构图、JDK 21 安装前提和运行命令、示例 REPL 会话、测试和 CI、实测 benchmark。
- `ARCHITECTURE.md` 包含 Mermaid 写入与读取流程图及模块职责。`FILE_FORMAT.md` 将实际实现与字节布局逐项核对。
- `DECISIONS.md` 至少写 8 条。候选主题为有序内存结构、按条同步、截断与 CRC 策略、稀疏索引间距、快照提交、tombstone 生命周期、范围合并、全量 compaction 和读写锁。WAL 决策要对比“末条完整帧 CRC 错也截断”：这种策略能继续打开库，却可能静默丢弃已确认写入。进程退出模型下，仅自动修复物理不完整的尾帧；完整帧 CRC 错时保留文件并报告损坏。记录采用 16 MiB WAL 上限的代价：反复覆盖同一 key 会触发 flush，产生只含一个 key 的小 SSTable。compaction 保留最新版本并回收旧表。每条记录动机、可行替代方案、代价及局限。
- `INTERVIEW_NOTES.md` 以英文为主，算法要点附中文，包含 30 秒和 2 分钟介绍、恢复和 compaction 讲解稿、12 个有参考答案的问题及 3 条基于实际实现的简历 bullet。问题覆盖 LSM vs B-tree、写放大、WAL、fsync 代价和并发。
- `READING_GUIDE.md` 用中文给出从 API 到持久化再到测试的顺序，每步写明读懂的标志。Maven 小节讲清 JDK、Wrapper、`pom.xml`、`test` 与 `verify`，以及在哪里读测试和覆盖率报告。
- `Benchmark` 用固定 seed 和临时数据目录，先预热，再分开测量写入与随机点读。读取负载在显式 flush 后运行并记录命中率。用 `System.nanoTime()` 计时，报告 ops/s、CPU、内存、磁盘、OS、JDK、数据量、阈值、同步策略、计时边界及缓存条件。报告实际跑出的结果，必要时多次运行并注明汇总方法。
