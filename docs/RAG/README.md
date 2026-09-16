# PostgreSQL 运维文档语料与入库

本目录存放 PostgreSQL 18 官方文档转换而来的 markdown 语料，以及配套的批量入库脚本。

## 目录结构

```
docs/RAG/
├── markdown/        141 个 markdown 文件（PostgreSQL 18 官方文档）
├── import_docs.py   批量上传脚本（HTTP 调用后端，无需直连数据库）
└── README.md        本文件
```

## 语料说明

- 来源：PostgreSQL 18 官方文档，经 HTML → markdown 转换。
- 质量：141/141 带 YAML frontmatter（`title` / `source_url`），零 HTML 残留、零乱码、无截断、md5 无重复。
- 已知噪声（均在**解析阶段**归一化，磁盘原文不动）：
  - `U+00A0` 不换行空格（1,300+ 处 / 136 文件）、零宽空格、BOM
  - 标题行尾部残留的 ` #` 锚点标记（数百处 / 103 文件）
  - `\_` 反斜杠转义泄漏（全部文件）
  - 缩进代码围栏（86 处 / 14 文件，形如 `    ``` `）
- 结构统计：2,230 表格行 / 26 文件，517 个代码块 / 1,034 围栏行，133/141 文件正文无 H1。

## 入库链路

上传 → MinIO → RocketMQ → 解析 → 分块 → 向量化（百炼 `text-embedding-v4`，1536 维）→ pgvector + Elasticsearch 双写。

改造后与旧链路的差别（仅对 markdown 生效，PDF/纯文本路径不变）：

| 环节 | 旧链路 | 新链路 |
|---|---|---|
| 清洗 | `DocumentParser.cleanText()` 抹平空行与缩进 | `MarkdownDocumentParser` 保留空行与缩进 |
| 分块 | `RagAsyncProcessor.splitText()` 按标点 400 字符切 | `MarkdownChunker` 标题感知，目标 800 字符 |
| 元数据 | 仅 `fileId/chunkIndex/text/length/offset` | 追加 `source_title` / `source_url` / `section_path` |

分块器把代码围栏、表格、连续正文行各视为**原子块**（永不从中间切开），分块边界优先落在标题层级上，
并为每个 chunk 前置小节面包屑（如 `VACUUM > Synopsis`）。面包屑直接写进 `chunk_text`，
因此会自然进入向量、关键词索引、rerank 输入与 UI 展示，片段脱离原文后仍能自证归属。

小节边界是**优先**断点而非强制断点：小节换了、但当前缓冲还不到 200 字时继续往后装。
本批 60 个文档里 42 个小节的正文不足 200 字（`## See Also` 只有 28 字），严格按标题切会切出一批
既嵌不出向量、也几乎召回不到的碎块。合并后的块只带一条面包屑，规则是
**能盖住块内全部小节的最深路径，否则退到它们的公共父级**：

- 嵌套合并（`VACUUM` 的导语并入 `VACUUM > Synopsis`）标深的那个，退到根会让同文档的块名字雷同；
- 并列合并（`… > Notes` 与 `… > Examples`）谁都不偏袒，退到 `…`，避免把一半内容挂错小节名。

实测 1,581 个块里 48 个跨小节（3.0%），其中 26 个退到公共父级、22 个标了深侧小节。

### 三级尺度

| 原子块 | 超过多少才切 | 怎么切 |
|---|---|---|
| 代码围栏 / 表格 | 2,400 字符 | 整行贪心填充，预算 800（表格再扣掉表头宽度）；代码片段补回围栏，表格片段补回表头（列名 + 分隔行） |
| 正文 | 800 字符 | 按 `.` 递归细分，整句仍超长才退到 `,`；句子贪心拼回约 800，尾段不足 200 并入前段 |

代码与表格的阈值更宽，因为结构完整比块大小重要：切开的大表格若不补表头，数据行读不出列含义，
也不再是合法的 markdown 表格。正文没有这层负担，超过目标大小就该切。

「约 800」不是按字符数切，而是**逐行装入**：先算预算（代码 800；表格 800 − 表头宽度），
然后一行一行往缓冲里追加，加不下这一行就先输出缓冲、再另起一段。由此有三条推论：

- 表格片段的总长**含重复表头**在内不超过预算，所以看起来都不超过 800；
- 一行**永不截断**，所以比预算还长的那一行会独占一段、把该段顶到 800 以上——
  本批 8 个表格片段如此，最大的一段是 2,245 = 表头 25 + 分隔行 + 一行 2,211 字符的单元格；
- 同理，下一行只要装不下就立刻收尾，片段也可能远**低于** 800
  （`functions-admin.md` §9.28.3 实测 735 / 261 / 680 / 2245 / 770 / 716 就是这种参差）。

本批代码围栏块 182 个、**没有一个超过 2,400**（最大 1,744），所以代码块从不被切开，
超 800 的 9 个代码 chunk 全是整块保留；表格块 76 个、其中 22 个超过 2,400，是唯一的切分来源。

任何一行都**整行保留，绝不截断**：本批 `functions-admin.md` 与 `monitoring-stats.md` 的表格里
存在单行上千字符的单元格（最长 2,211），旧实现在这里丢过 7 行共 4,106 字符且断在词中。

标点切分把标点归前一段、空白归后一段，拼回去与原文逐字符一致；标点后必须跟空白或到文末，
否则会把 `PostgreSQL 18.0`、`1,024` 这类数字切断。

重叠区（100 字符）**只加在同一个原子块被切开的相邻片段之间**，原子块的第一段不加：
跨原子块拼接会把上一块的收尾内容注进下一块的开头（代码片段或没有表头的表行随之混入）。
重叠区起点对齐到行首或句读标点之后，不会注入半截词句。

实测（60 个文档，直接跑真实语料）：分块 **1,581**，最长正文 **2,245**（表格块），
超过 800 的块 **52**（表格 43 + 代码 9 + 正文 **0**）；78 个块带重叠区，
起点全部落在行首或句子边界，断在词中 **0** 处；正文行核对 4,951 行，**丢失 0**。

## 入库前置

1. 执行 DDL（项目无 Flyway/Liquibase，需人工在 `rag_db` 执行一次）：

   ```sql
   src/main/resources/db/rag_chunk_metadata.sql
   ```

   该脚本为 `text_chunk` 追加 `source_title` / `source_url` / `section_path` 三列，幂等（`IF NOT EXISTS`）。

2. 确认 MinIO（`rag-bucket`）、RocketMQ（NameServer + Broker）在跑，后端 8084 已启动。
   上传后状态若卡在 `UPLOADED` 不前进，说明 MQ 消费者没起来。

## 批量上传脚本

`import_docs.py` 通过 HTTP 接口上传，登录凭据与后端地址走环境变量：

```bash
export AGENT_BASE_URL=http://localhost:8084   # 可选，默认即此值
export AGENT_USERNAME=<用户名>
export AGENT_PASSWORD=<密码>
```

由于项目约定 Python 文件不写注释，脚本的行为说明集中在此处：

- **上传**：`POST /agent/kb/documents`，multipart 字段名 `file`。
- **轮询**：`GET /agent/kb/documents`，直到状态不再处于 `UPLOADED` / `PARSED`。
- **重试**：现有后台链路失败后置 `FAILED` 且**不重试**（`RagAsyncProcessor` 刻意不抛异常），
  重试逻辑补在脚本层——每轮失败的文件先删除旧记录（避免唯一名冲突）再重传。
- **幂等**：已 `EMBEDDED` 的文件默认跳过；`--force` 时删除旧记录重导。

### 常用命令

```bash
python import_docs.py --only sql-vacuum    # 单文档端到端验证
python import_docs.py --limit 5            # 小批量验证
python import_docs.py                      # 全量 60 个高相关文档
python import_docs.py --force              # 强制重导
```

### 参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `--only STEM ...` | 全部 | 只处理指定文件（不含 `.md` 后缀） |
| `--limit N` | 0（不限） | 只处理前 N 个 |
| `--force` | 关 | 已入库的也删除重导 |
| `--retries N` | 2 | 失败重试轮数 |
| `--poll N` | 3 | 状态轮询间隔秒数 |
| `--timeout N` | 900 | 单轮等待超时秒数 |

脚本退出码：全部 `EMBEDDED` 返回 0，否则返回 1，并在末尾打印失败清单。

## 入库范围：为什么是这 60 个

141 个文件中筛出日常运维主线（`TARGET_STEMS`，见脚本）：

- **备份恢复**：`backup-dump`、`backup-file`、`app-pgbasebackup`、`continuous-archiving`、`wal-*`、`checksums`
- **锁与并发**：`explicit-locking`、`sql-lock`、`monitoring-locks`、`view-pg-locks`、`transaction-iso`
- **性能调优**：`performance-tips`、`using-explain`、`sql-explain`、`sql-analyze`、`routine-reindex`、`sql-reindex`、`routine-vacuuming`、`sql-vacuum`
- **参数配置**：`config-setting`、`runtime-config-*`（18 个）
- **监控**：`monitoring-stats`、`monitoring-ps`、`progress-reporting`、`view-pg-stats`、`logfile-maintenance`
- **复制**：`high-availability`、`warm-standby*`、`hot-standby`、`logical-replication*`、`different-replication-solutions`
- **故障排查**：`diskusage`、`mvcc-serialization-failure-handling`、`functions-admin`、`predefined-roles`

未纳入的 81 个以 SQL 语法参考、扩展开发、客户端接口等与运维决策弱相关的章节为主。

语料普遍带**无链接目录块**，入库时是纯噪声，分块器会整块跳过：

- PG 文档有两种目录写法：带 `**Table of Contents**` 标记的章封面页，以及标题下直接罗列小节编号
  （如 `19.8.1. Where to Log`）而不带任何标记的形式。后者没有标记可依，只能靠形态识别。
- **141 个文件中 46 个含目录块**，本批 60 个中命中 **24 个**。统计口径：把真实的
  `MarkdownDocumentParser`（归一化之后）与 `MarkdownChunker.isTocEntry` 跑在这 141 个文件上。
- 判定规则：连续 **3 行以上**「点分编号 + 标题」且每行不超过 100 字符即为目录，整块跳过；
  不足 3 行按正文处理，避免误伤正文里本就存在的编号列表。
- 实测危害（改造前）：查询「hot standby 是什么」时 `hot-standby.md` 的目录块排到第 1 位，真正的定义被挤到第 2；
  `monitoring-stats.md` 第 0 片 789 字几乎全是章节名，占满一个片位。
- 改造后复测：该查询第 1 位变成 `26.4. Hot Standby` 的定义段；60 个文档中残留目录块的由 24 降到 **0**，
  该轮总分块 1518 → 1502（后续正文按句切分后为 1,581）；章封面页目录后的导语不会被连带丢弃
  （`landingPageIntroSurvivesTocSkip` 断言）。

## 验证

1. **分块器单测**：`./mvnw test -Dtest=MarkdownChunkerTest`（纯 JUnit，不起 Spring 上下文，直接读真实语料）。
2. **单文档端到端**：`python import_docs.py --only sql-vacuum`，再查 `GET /agent/kb/documents/{fileId}/chunks`
   核对围栏完整、面包屑存在（应形如 `VACUUM > Synopsis`）、`section_path` / `source_url` 有值。
3. **检索验收**（语料英文、提问中文，属跨语言检索，需重点观察）：`POST /agent/kb/retrieve`
   - 「VACUUM FULL 会阻塞读写吗」→ `sql-vacuum.md`
   - 「如何查看当前锁等待」→ `monitoring-locks.md` / `view-pg-locks.md`
   - 「流复制怎么做故障切换」→ `warm-standby-failover.md`
   - 「autovacuum 什么时候触发」→ `runtime-config-autovacuum.md` / `routine-vacuuming.md`
   - 「hot standby 是什么」→ `hot-standby.md` 的 `26.4. Hot Standby` 定义段
     （该查询历史上曾被 `hot-standby.md` 自己的目录块挤到第 2 位，用于回归目录跳过逻辑）
4. **批量导入**：`python import_docs.py`，核对无 `FAILED`；chunk 总数应明显低于旧 400 字符切分。
5. **回归**：上传一个 PDF，确认 `cleanText()` 路径与新增三列留空未破坏原流程。

> 改动分块器之后，必须**重启后端 + `--force` 重导**才会生效，两个条件缺一不可：
> 分块发生在进程内，只改源码不重启，切出来的分块与改动前一模一样（表现为「改动看起来没生效」）；
> 不 `--force` 则已 `EMBEDDED` 的文档被直接跳过，库里留的还是旧分块。
