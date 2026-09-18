# Task: Database Anomaly Diagnosis

---

## Task Metadata

**Task Name**

Database Anomaly Diagnosis

**Description**

在数据库指标出现异常、或用户直接描述"卡/慢/锁/膨胀/复制延迟"等症状时，逐层下钻定位根因：先看活跃会话与等待事件分布，再按等待事件指向的方向调用锁阻塞、慢查询、表膨胀、复制状态、表访问等专用工具，最终给出带具体证据（会话 pid、持锁对象、持有时长、表名、索引名）的根因判断。

该任务依赖下钻工具从 pg_stat_activity、pg_locks、pg_blocking_pids、pg_stat_user_tables、pg_stat_user_indexes、pg_stat_replication、pg_replication_slots 等系统视图获取真实证据，不允许依赖模型已有知识编造会话、锁、表或复制信息。

**与 database_metrics 的分工**

- database_metrics 负责"发现异常"：采八项指标并判断哪一项越界；
- db_diagnosis 负责"定位根因"：拿到异常项后继续下钻，直到能指出具体是哪个会话、哪张表、哪个对象。

指标类请求若只停留在"某项指标偏高"而不继续下钻，等于没有完成任务。

---

## Task Objective

完成用户的数据库异常排查请求，并返回带真实证据的根因结论。

完成任务需要满足以下条件：

- 正确识别目标数据库实例（支持巡检配置中的业务名，如 `rag库`，或 `host:port` 字面地址）；
- 至少完成一次下钻调用，拿到会话、锁、表、索引或复制层面的具体证据；
- 结论中必须引用工具返回的具体数值或标识（如 pid、usename、state、持锁对象、表名、延迟字节数）；
- 无法定位根因时，明确说明已排查的范围与仍需补充的信息，而不是给出泛泛建议。

---

## Task Knowledge

### 数据库实例识别

`instance` 参数有两种写法，可任选其一：

- **巡检配置中的业务名**：例如 `rag库`。此时可以省略 `database` 参数，系统会自动解析出 host、port 与该实例配置的库名。
- **字面地址 host:port**：例如 `localhost:5432`。此时**必须**同时提供 `database`。

用户可能采用以下方式描述数据库实例：

- rag库 现在很卡，帮我看看是谁在锁表
- 查一下 localhost:5432 上 mydb 库的活跃会话
- 192.168.1.100:5432/order_db 主从延迟一直在涨，排查一下
- 数据库有点慢，看看卡在哪里

如果用户既没有给出可识别的业务名，也没有给出 host:port，应向用户询问，不得自行猜测连接信息。

---

### 下钻方向决策表

按下表选择下一步调用哪个工具。**注意：只有第一步是固定的，第二步之后取决于上一步返回的实际值**（例如看到等待事件为 Lock 才去查阻塞链，发现阻塞方是 `idle in transaction` 才去关注该会话的事务时长）。

| 观测到的情况 | 下一步工具 | 判断依据 |
|---|---|---|
| 不知道卡在哪一类资源，需要总览 | `getWaitEventDistribution` | 先看等待事件大类分布 |
| 活跃会话数偏高、怀疑长事务 | `listActiveSessions` | 逐会话看 state 与 xactSeconds |
| 等待事件大类为 `Lock`，或用户明确提到锁表/锁等待 | `getBlockingChains` | 拿到阻塞方 pid、持锁对象、持有时长 |
| 等待事件大类为 `IO`、`LWLock`，或某条 SQL 耗时高 | `getTopSlowQueries` | 定位具体 SQL 及其执行统计 |
| 死元组比例高、表膨胀、怀疑 VACUUM 未跟上 | `getVacuumAndBloatStatus` | 区分"autovacuum 会追上"与"被长事务卡住清理水位" |
| 复制延迟高、WAL 堆积、磁盘被 WAL 占满 | `getReplicationStatus` | 看延迟落在发送/写入/刷盘/重放哪一段，以及非活跃槽位 |
| 顺序扫描多、索引使用率低、单表查询慢 | `getTableAccessStats` | 找出被全表扫描的表与从未使用的索引 |

补充说明：

- 上述顺序不是流水线，允许跳步。用户已经明确说"谁在锁表"时，可以直接调 `getBlockingChains`。
- 一个方向排查完没有发现异常时，回到等待事件或会话列表换个方向，不要把同一个工具重复调用。
- 不要在证据不足时给出根因结论；宁可多调用一次工具。

---

### 关键字段解读

**会话与等待事件**

- `state`：`active` 为正在执行；`idle in transaction` 表示事务已开启但当前未执行语句——这是最常见的锁持有者形态，也是最需要关注的信号。
- `waitEventType` / `waitEvent`：`Lock` 表示在等锁；`IO` 表示等磁盘；`Running/CPU`（waitEventType 为 null）表示正在跑或纯 CPU 消耗。
- `xactSeconds`：事务已开启时长。远大于 `querySeconds` 说明是"事务开着但没在跑语句"，典型的长事务持锁。
- `xminAge`：该会话 `backend_xmin` 的年龄。数值大说明它钉住了清理水位，导致死元组无法回收。

**锁阻塞**

- `blockerLockRelation`：被争用的对象（已优先取普通堆表，而不是索引）。
- `blockerXactSeconds`：阻塞方事务已开启时长，是判断"是否该 kill 该会话"的关键依据。
- `rootBlocker`：为 true 表示该阻塞方自身不在等待锁，是整条阻塞链的源头，优先处理它。
- `blockedWaiterCount`：被该阻塞方挡住的会话数，用于判断影响面。
- 返回的是扁平边列表。多条边通过 pid 串联成链，例如 A 挡住 B、B 又挡住 C，需要你自己按 pid 串起来再解释给用户。

**表膨胀**

- `deadTuples` / `deadRatioPct`：死元组绝对条数与比例。
- `oldestXminHolder`：持有最旧 xmin 的会话。若非空且 `xminAge` 很大，说明清理水位被钉住，autovacuum 想做也做不了。
- `oldestXminHolderNote`：出现该字段说明后端未返回数据（权限不足或无活跃快照），此时改用 `longestRunningTransactions` 判断。
- `longestRunningTransactions`：始终可用的兜底证据。若其中存在长时间 `idle in transaction` 或长事务，配合死元组高就是"长事务卡住清理"的特征。
- 若 `lastAutovacuum` 较近且死元组在下降，属于"autovacuum 正在追上"，不需要干预。

**复制**

- `role`：`primary` 或 `standby`，决定后续字段形态。
- 主库 `replicas[].totalLagBytes` 与 `replayLagSeconds`：延迟总量。`sentToWriteBytes` / `writeToFlushBytes` / `flushToReplayBytes` 三个分段差值用于判断延迟卡在网络上（发送→写入）、从库磁盘上（写入→刷盘）还是从库重放上。
- 备库 `standby.unreplayedBytes`：已接收但未重放的字节数。
- `inactiveSlotCount`：非活跃槽位数量。大于 0 且 `retentionBytes` 持续增长，说明有消费者停止拉取 WAL，磁盘会被持续占用，这是需要立刻处理的问题。

**表访问**

- `seqScan` 高且 `seqTupRead` 远大于 `liveTuples`：该表被反复全表扫描。
- `indexUsagePct`：索引扫描占比。越低说明越依赖全表扫描。
- `unusedIndexes`：这些索引 `idxScan` 为 0，既不加分查询又拖慢写入。

---

## Task Workflow

执行当前任务时，应遵循以下流程：

### Step 1

分析用户请求，提取以下信息：

- 数据库实例（业务名如 `rag库`，或 `host:port`）
- 数据库名称（使用业务名时可省略）
- 用户描述的症状关键词（卡顿、锁表、延迟、膨胀、慢等）

---

### Step 2

如果既无法确定实例业务名，也没有 host:port：

结束当前执行，询问用户提供缺失的实例信息。不得调用工具，不得猜测连接信息。

若用户提供了 host:port 但没有库名，同样询问库名。

---

### Step 3

信息齐备后，根据症状选择第一个工具：

- 症状笼统（"很卡""有点慢"）→ 先调 `getWaitEventDistribution` 总览，必要时并发调用 `listActiveSessions` 看具体会话；
- 症状明确指向某一类（"谁在锁表""主从延迟在涨""表膨胀得厉害"）→ 直接调用对应工具。

---

### Step 4

根据上一步返回的实际内容决定下一步（这是本任务的核心，不要跳过）：

- 复用上一步已经解析出的 `instance` 与 `database`，**不需要也不应该让用户重新提供**：`instance` 沿用同一个值
  （用户给的是业务名就继续用业务名，是 host:port 就继续用 host:port），需要时可把上一步返回的 `database` 填进去。
- `argument_sources` 按**值的实际来处**标注，不要一律标 `TOOL_OUTPUT`：
  - 值逐字出现在用户本轮原话里（例如用户说了 `rag库`，你继续用 `rag库`）→ `EXPLICIT_CURRENT`；
  - 值不在用户本轮原话里、而是取自本轮此前某次**成功**工具调用的返回结果（例如你填的 `database` 来自上一步返回的 `database`，或改用上一步回显的 `resolvedInstance`）→ `TOOL_OUTPUT`。
  - 标错来源会被参数校验拒绝，所以拿不准时优先沿用用户原话里的写法。
- 若某一步返回 `success: false` 或局部 `*Error` 字段，说明该方向取证失败，换一个方向继续，不要反复重试同一个工具。

最多完成 2–3 次下钻即可收敛，不要为了凑步数重复调用。

---

### Step 5

证据充分后输出结论：

- 先说根因：谁（pid / usename / applicationName）在做什么，卡了多久，影响了什么（被挡住的会话数、堆积的表或 WAL）；
- 再给证据：引用工具返回的具体字段值，不要改写或估算；
- 最后给处置建议：指出应该优先终止哪个会话、终止前需要确认什么（例如事务已开启时长、是否在跑关键 DDL）、或应该对哪张表执行 VACUUM / 补哪个索引；
- 涉及终止会话、重建索引等写操作时，**只给建议不代为执行**（本任务下的工具全部为只读）；
- 若证据仍不足，说明已排查范围与需要用户补充的信息。

不得修改工具返回的原始数据。

---

## Tool Specification

当前任务可使用如下工具。所有工具均为只读，两个参数的含义一致。

### 通用参数说明

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| instance | string | 是 | 数据库实例地址（host:port），或巡检配置中的业务名（如 `rag库`） |
| database | string | 否 | 数据库名称；使用业务名时可省略，默认取该实例配置的库名；使用 host:port 时必填 |

---

### Tool

name

getWaitEventDistribution

description

按等待事件大类（wait_event_type）与具体等待事件（wait_event）统计指定数据库当前所有客户端会话的分布，返回各大类会话数、各具体等待事件的会话数及占比最大的等待事件大类，用于判断数据库整体卡在锁、IO、客户端等哪一类资源上

### Parameters

```json
{
  "instance": "string",
  "database": "string"
}
```

---

### Tool

name

listActiveSessions

description

列出指定数据库当前所有非空闲的客户端会话明细（pid、用户、应用、客户端地址、状态、等待事件、backend_xmin 及其年龄、查询已执行时长、事务已开启时长、状态停留时长、SQL 前 200 字符）并按状态汇总计数，用于排查活跃会话异常、长事务与锁等待源头

### Parameters

```json
{
  "instance": "string",
  "database": "string"
}
```

---

### Tool

name

getBlockingChains

description

获取指定数据库当前的锁阻塞关系（阻塞方 pid/用户/应用/状态/事务已开启时长/backend_xmin/正在执行的 SQL、持有锁的模式与对象、是否链源头，以及等待方 pid/用户/状态/已等待时长/正在执行的 SQL、被该阻塞方挡住的会话数），用于定位锁等待的根因会话与长事务

### Parameters

```json
{
  "instance": "string",
  "database": "string"
}
```

---

### Tool

name

getTopSlowQueries

description

查询数据库中平均执行时间最长的Top 10慢查询，基于pg_stat_statements扩展，返回查询文本、执行次数、各维度耗时及缓冲区命中率等关键信息

### Parameters

```json
{
  "instance": "string",
  "database": "string"
}
```

---

### Tool

name

getVacuumAndBloatStatus

description

获取指定数据库中死元组最多的表明细（表名、活/死元组数、死元组比例、最近 vacuum/autovacuum/analyze 时间、autovacuum 次数、表体积），并附带持有最旧 backend_xmin 的会话与运行时间最长的事务，用于判断表膨胀是由长事务/旧快照卡住清理水位、还是写入量超过 autovacuum 清理能力，或自动清理即将追上

### Parameters

```json
{
  "instance": "string",
  "database": "string"
}
```

---

### Tool

name

getTableAccessStats

description

获取指定数据库中被顺序扫描最多的表（表名、顺序扫描次数与读取元组数、索引扫描次数与回表元组数、增删改计数、活元组数、表体积、索引使用率百分比），以及这些热表上扫描次数为 0 的未使用索引（索引名、扫描次数、索引体积），用于定位缺失索引或冗余索引

### Parameters

```json
{
  "instance": "string",
  "database": "string"
}
```

---

### Tool

name

getReplicationStatus

description

获取指定数据库的复制状态：自动区分主库/备库，主库返回每台从库的连接状态、同步模式、四个 LSN 位置与两两差值、write/flush/replay 延迟秒数，备库返回接收与重放 LSN 差值和重放延迟时长；同时返回复制槽列表、各槽位保留的 WAL 字节数与非活跃槽位数，用于排查复制延迟与 WAL 堆积

### Parameters

```json
{
  "instance": "string",
  "database": "string"
}
```
