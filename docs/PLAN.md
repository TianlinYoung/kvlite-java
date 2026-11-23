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
| `Repl` / `Benchmark` | REPL 解析命令和展示结果。基准程序独立执行可复现的测试负载。 |

阶段 3 先把合并规则放在引擎的一处，等出现清晰的职责边界再决定是否提取成类。每个源文件开头一句话说明职责。代码首次用到 `ByteBuffer`、`RandomAccessFile`、fsync 或 try-with-resources 时，用短注释解释它在这里解决什么问题。需要在多个读线程中定位文件时，每次读操作使用自己的 `RandomAccessFile`，避免共享文件指针。

## 3. 数据路径和恢复

### WAL 与内存表

1. 校验输入，构造 value 或 tombstone。
2. 循环写完 WAL 帧，调用 `FileChannel.force(true)` 请求持久化文件数据及元数据。
3. 更新 memtable。阶段 2 起，达到阈值时同步 flush。默认阈值草案为 1,024 个不同 key，包含 tombstone。仅反复改同一个 key 时 WAL 仍可能变大，显式 flush 可回收。
4. WAL 追加或同步失败时关闭写入路径并要求重新打开。调用者收到 I/O 错误时，重启后该操作可能已生效。自动 flush 失败也向调用者报告同样的结果不确定性，实例停止接收操作并允许关闭。

阶段 1 使用 `wal-0000000000000000001.log`。启动时从 header 后顺序读完帧，按日志顺序重建 `TreeMap`。EOF 位于完整帧的末尾是正常结束。EOF 位于不足 8 字节的帧头中，或在通过帧头校验后出现在帧体或 CRC 内，允许截断至最近的完整帧位置并强制同步，然后才允许继续追加。完整帧的 CRC、非法长度或非法记录编码报错，保留原文件。保留从损坏处开始的字节有助于定位问题。成对损坏且恰好通过长度反码校验的长度字仍可能被当成截断，本格式无法可靠区分这种情况。

### Flush 与提交点

阶段 2 开始，manifest（当前文件集合的快照）决定启动时读哪些 SSTable 和哪一个 WAL。每份快照有递增编号，通过同目录临时文件发布到从未使用过的最终文件名。启动选择最高编号的最终 manifest，验证失败就报错。阶段 2 第一次打开阶段 1 的数据目录时，若还没有任何最终 manifest，就以阶段 1 的 WAL 为基础、空 SSTable 列表初始化第一份快照。创建新库时先建立并同步初始 WAL。发现最终 manifest 引用的文件缺失应报错。

1. 将 memtable 按 key 顺序写入临时 SSTable。同步文件、关闭文件句柄，然后原子移动到新的最终文件名。创建并同步一个只有 header 的新 WAL。
2. 写入、同步并关闭新 manifest 的临时文件，再将其原子移动到新编号的最终文件名。这个移动是提交点。若文件系统不支持同目录原子移动，报错并停止发布。
3. 提交后切换内存中的表集合和 WAL 句柄，清空 memtable。旧 WAL 及旧 manifest 在新状态确认生效后才允许清理。关闭旧文件句柄后再尝试清理。清理失败可保留文件待重试，读路径只认当前快照。

提交前退出时，旧 manifest 仍引用旧 WAL，恢复会包含本次 memtable 的内容。提交后退出时，新 manifest 引用已发布的表和新 WAL。后续写入始终追加到新 WAL。启动忽略 `.tmp` 和快照未引用的表或日志，分配新编号时计入遗留文件，避免复用名字。空 memtable 的 flush 直接返回。设计和测试以本地盘上的进程退出为范围。文件同步加原子改名未建立跨平台的目录项断电持久化保证。 [Java 21 `FileChannel.force`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean)) · [Java 21 `Files.move`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...))

### 点查找、范围与 compaction

- 点查找先读 memtable，再从新到旧查 SSTable。每张表对索引做 floor 查找，跳到不大于目标的最近采样 key，再顺序读取到下一个采样点或数据区末尾。目标在第一个采样 key 之前时可直接返回缺失。
- 范围扫描让每张表从 `fromInclusive` 对应的索引位置开始读，到 `toExclusive` 停止。按最旧 SSTable 到最新 SSTable、最后 memtable 的顺序，把记录覆盖写进一个临时 `TreeMap`。最后过滤 tombstone 并按 key 返回。扫描需要与该范围内不同 key 数量成正比的临时内存。
- Compaction 将所有当前磁盘表按同样的「旧到新覆盖」规则合并。保留 tombstone 直到所有输入合并完成，再一次移除旧值和 tombstone。由于没有更旧的存活磁盘表，被删的值无法从旧表重新出现。memtable 和活动 WAL 保持原样，始终比结果表更新。合并后若没有值，提交空表列表。没有输入表时直接返回。沿用 flush 的 manifest 提交点，先发布结果，再切换快照，最后清理输入文件。内存上限受参与合并的不同 key 数量影响，运行期间会阻塞写入。

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
| 1 | Maven、wrapper、CI、Memtable、WAL 和引擎恢复路径 | JUnit 5 测覆盖、删除、空值、Unicode、关闭重开。对一个完整 WAL 的最后一帧逐字节截断，验证只恢复完整前缀、截尾后可继续写。篡改完整末帧和中间帧 CRC、篡改单个长度字，验证打开失败且文件不变。 |
| 2 | SSTable 读写、阈值与手动 flush、manifest、点读路径 | 覆盖稀疏索引两侧、空表、损坏 footer、较新 tombstone 遮挡旧值和跨表读。构造提交前后目录状态，验证重开、再次写入、再次 flush 及阶段 1 WAL 升级。 |
| 3 | Compaction、range scan、模型对照 | 固定种子执行至少 5,000 步 `put/delete/get/flush/compact/scan`，加入重开。随机读与扫描逐次比对参考 `TreeMap`，每次 flush、compact 和重开后检查全量映射。失败输出 seed、步骤与操作。另测全删、重复覆盖、提交前后重开与 memtable 尚未 flush 的 compaction。 |
| 4 | REPL、简单 Java benchmark、同实例并发、其余文档 | REPL 支持 `put/get/delete/scan/stats/compact`。使用一个 `ReadWriteLock`，读锁覆盖 get、scan、stats，写锁覆盖更新、flush、compact、close。scan 在锁内完成并返回结果副本。验证多线程写读和关闭行为，实跑并记录基准。 |

阶段 1 起，GitHub Actions 在 push 时用 JDK 21 跑 `./mvnw verify`，可同时在 PR 上运行。Windows 本地对应 `.\mvnw.cmd verify`。Wrapper 固定 Maven 版本。JUnit 5 是测试依赖，JaCoCo、编译器和测试运行器属于构建插件，运行时只依赖 JDK。每个可执行组件有对应单元测试，JaCoCo 在 `verify` 检查整个主代码的行覆盖率至少 85%。以后每阶段 PR 都列出改动、阅读顺序、实际测试结果和假设。用户与 Claude 审阅确认后再进入下一阶段。 [Apache Maven Wrapper](https://maven.apache.org/tools/wrapper/)

## 6. 阶段 4 文档和基准清单

- `README.md` 包含一句话介绍、架构图、JDK 21 安装前提和运行命令、示例 REPL 会话、测试和 CI、实测 benchmark。
- `ARCHITECTURE.md` 包含 Mermaid 写入与读取流程图及模块职责。`FILE_FORMAT.md` 将实际实现与字节布局逐项核对。
- `DECISIONS.md` 至少写 8 条。候选主题为有序内存结构、按条同步、截断与 CRC 策略、稀疏索引间距、快照提交、tombstone 生命周期、范围合并、全量 compaction 和读写锁。每条记录动机、可行替代方案、代价及局限。
- `INTERVIEW_NOTES.md` 以英文为主，算法要点附中文，包含 30 秒和 2 分钟介绍、恢复和 compaction 讲解稿、12 个有参考答案的问题及 3 条基于实际实现的简历 bullet。问题覆盖 LSM vs B-tree、写放大、WAL、fsync 代价和并发。
- `READING_GUIDE.md` 用中文给出从 API 到持久化再到测试的顺序，每步写明读懂的标志。Maven 小节讲清 JDK、Wrapper、`pom.xml`、`test` 与 `verify`，以及在哪里读测试和覆盖率报告。
- `Benchmark` 用固定 seed 和临时数据目录，先预热，再分开测量写入与随机点读。读取负载在显式 flush 后运行并记录命中率。用 `System.nanoTime()` 计时，报告 ops/s、CPU、内存、磁盘、OS、JDK、数据量、阈值、同步策略、计时边界及缓存条件。报告实际跑出的结果，必要时多次运行并注明汇总方法。
