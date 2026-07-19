# Task: Slow Query Analysis

---

## Task Metadata

**Task Name**

Slow Query Analysis

**Description**

查询数据库中平均执行时间最长的 Top 10 慢查询，基于 pg_stat_statements 扩展视图获取真实的查询性能统计数据，包括执行时间、执行频率、缓冲区命中率等关键指标，用于慢查询定位和性能优化。

该任务依赖慢查询工具从 pg_stat_statements 获取真实数据，不允许依赖模型已有知识编造慢查询信息。

---

## Task Objective

完成用户的慢查询分析请求，并返回 Top 10 慢查询列表与分析结论。

完成任务需要满足以下条件：

- 正确识别用户需要分析的数据库实例地址和数据库名称；
- 使用慢查询工具获取 Top 10 慢查询数据；
- 对返回的慢查询进行解读，指出性能问题并给出优化建议。

---

## Task Knowledge

### 数据库实例识别

用户可能采用以下方式描述数据库实例：

- 帮我查一下 localhost:5432 上 mydb 库的慢查询
- 看看 192.168.1.100:5432/test_db 有哪些慢查询
- 分析生产库 10.0.0.50:5432/order_db 的慢查询 Top 10
- 最近数据库很慢，帮我排查一下有没有慢查询

如果用户未明确提供数据库实例地址或数据库名，应向用户询问。

不得自行猜测数据库连接信息。

---

### pg_stat_statements 扩展说明

慢查询数据来自 PostgreSQL 的 pg_stat_statements 扩展，该扩展会持续收集数据库中所有 SQL 语句的执行统计信息。

如果目标数据库未安装该扩展，工具将返回错误提示，此时应告知用户需要在目标数据库执行：

```sql
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
```

---

### 慢查询指标解读

#### 执行时间指标

- **meanExecTimeMs（平均执行时间）**：单次执行的平均耗时，是判断慢查询的核心指标
- **totalExecTimeMs（总执行时间）**：自统计以来所有执行的总耗时，反映查询对系统的整体影响
- **maxExecTimeMs / minExecTimeMs（最大/最小执行时间）**：反映查询执行时间的波动范围
- **stddevExecTimeMs（标准差）**：执行时间波动程度，标准差大说明查询性能不稳定，可能与参数化查询的不同参数值有关

#### 执行频率指标

- **calls（执行次数）**：自统计以来的总执行次数，结合 meanExecTimeMs 可判断是"高频慢查询"还是"偶发慢查询"

#### 缓冲区指标

- **sharedBlksHit / sharedBlksRead**：共享缓冲区命中/读取块数
- **bufferHitPct（缓冲区命中率）**：命中率低说明查询频繁触发磁盘 I/O，建议 > 95%

---

### 慢查询优化建议

根据慢查询的不同特征，给出对应的优化方向：

- **平均耗时长 + 执行次数少**：优先关注 SQL 本身性能，检查执行计划是否有全表扫描、索引缺失等问题
- **平均耗时长 + 执行次数多**：高优先级，既要优化 SQL 本身，也要考虑缓存或应用层优化
- **标准差大**：检查参数化查询是否存在参数倾斜（某些参数值导致不同的执行计划）
- **缓冲区命中率低**：检查 shared_buffers 大小是否充足，或是否存在大量冷数据扫描
- **rows 异常大**：检查是否缺少 LIMIT 或分页逻辑，是否存在意外的笛卡尔积

---

## Task Workflow

执行当前任务时，应遵循以下流程：

### Step 1

分析用户请求。

判断是否明确包含以下信息：

- 数据库实例地址（host:port）
- 数据库名称

---

### Step 2

如果缺少数据库实例地址或数据库名：

结束当前执行，

询问用户提供缺失的连接信息。

不得调用工具。

---

### Step 3

如果用户要求重置慢查询统计：

先向用户确认重置操作的影响（将清空所有历史统计，无法恢复），

确认后调用 resetSlowQueryStats 工具。

---

### Step 4

确认以上信息均已获取：

调用慢查询工具。

---

### Step 5

等待工具返回结果。

根据返回结果进行解读：

- 依次列出 Top 10 慢查询，标注每条查询的核心问题（耗时长、频率高、命中率低等）；
- 对每条慢查询给出针对性的优化建议；
- 如果 slowQueries 为空，说明当前统计周期内没有显著慢查询，告知用户数据库运行良好；
- 如果存在 meanExecTimeMs 超过 1000ms 的查询，应重点标注。

不得修改工具返回的原始数据。

---

### Step 6

如果工具返回 success: false：

- 若提示 pg_stat_statements 未安装，告知用户执行 `CREATE EXTENSION IF NOT EXISTS pg_stat_statements` 后重试；
- 其他错误告知用户采集失败及失败原因，建议检查数据库连接信息和网络可达性后重试。

---

## Tool Specification

当前任务可使用如下工具。

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

参数说明：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| instance | string | 是 | 数据库实例地址，格式为host:port，例如：localhost:5432 |
| database | string | 是 | 数据库名称 |

---

### Tool

name

resetSlowQueryStats

description

重置pg_stat_statements统计数据，清空所有历史慢查询记录，重新开始统计

### Parameters

```json
{
  "instance": "string",
  "database": "string"
}
```

参数说明：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| instance | string | 是 | 数据库实例地址，格式为host:port |
| database | string | 是 | 数据库名称 |
