# kvlite-java 阅读指南

按一次写入、一次重开、一次读和一次归并的顺序阅读。每步用一个可以在代码上指出的行为判断自己是否读懂。

| 步骤 | 文件与入口 | 读懂标志 |
| --- | --- | --- |
| 1. 对外行为 | `Storage.java`、`Stats.java`、`Repl.java` | 能写出合法的 REPL 命令，解释空值、缺失、删除，以及 scan 边界和三个统计字段。 |
| 2. 写入内存 | `LsmStorage.write`、`EntryCodec`、`Memtable` | 能逐步指出输入校验、WAL 同步、内存更新与阈值检查，并解释热 key 为什么能触发 flush。 |
| 3. WAL 恢复 | `Wal.append`、`Wal.replay` 和 [文件格式](FILE_FORMAT.md) | 能手画一帧，指出 CRC 覆盖哪些字节，并区分可截断尾部、非法长度和坏 CRC。 |
| 4. 文件发布 | `LsmStorage.flushTable`、`TableSet.open`、`TableSet.commit` | 能分别列出 manifest 发布前后恢复所需的文件，解释为什么要验证后再清理。 |
| 5. 点读与范围读 | `FileSSTableReader.get/range`、`TableCursor`、`MergingIterator` | 能指出索引定位和点查每条记录的两段读，能手算两个表加 memtable 的同 key 胜者。 |
| 6. 归并与并发 | `LsmStorage.compact`、`SSTableWriter`、`ConcurrencyTest` | 能解释何时安全丢 tombstone，指出清理前在哪里关旧句柄，并说明读写锁和传播后台断言的方式。 |
| 7. 验证与测量 | `WalRecoveryTest`、`CompactionTest`、`StorageModelTest`、`ReplTest`、`BenchmarkWorkload` | 能区分构造崩溃目录的测试、5,000 步状态对照、REPL 运行与单线程吞吐基准。能复述基准把哪些操作排除在计时外。 |

## 用 Maven 查验

1. 用 `java -version` 检查 JDK 21，确保编译和运行使用可用的 Java 环境。
2. `./mvnw`（Windows 为 `.\mvnw.cmd`）按 `.mvn/wrapper/maven-wrapper.properties` 使用固定 Maven。首次运行可能需要下载 Maven 和构建依赖。
3. `pom.xml` 设定 Java 21 release，列出测试范围的 JUnit 5，以及编译、测试和 JaCoCo 插件。运行引擎只需要 JDK。
4. `./mvnw test` 运行测试。可用 `-Dtest=WalRecoveryTest` 只跟一组。Surefire 报告在 `target/surefire-reports/`。
5. `./mvnw verify` 运行测试、打包并执行 JaCoCo 85% 行覆盖门槛。打开 `target/site/jacoco/index.html` 查类与行，`jacoco.xml` 提供机器可读计数。基准入口类排除在覆盖检查外，实际负载和统计代码仍纳入检查。
