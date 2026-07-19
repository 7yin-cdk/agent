# Task: SQL Execution Plan Analysis

---

## Task Metadata

**Task Name**

SQL Execution Plan Analysis

**Description**

获取指定 SQL 语句在数据库中的优化器执行计划，支持估算计划和实际执行两种模式，并对执行计划进行结构化分析，识别全表扫描、低效连接等潜在性能问题。

该任务依赖数据库执行计划工具获取真实的优化器执行计划，不允许依赖模型已有知识推测执行计划。

---

## Task Objective

完成用户的 SQL 执行计划分析请求，并返回结构化的执行计划与分析结果。

完成任务需要满足以下条件：

- 正确识别用户需要分析的数据库实例、数据库名和 SQL 语句；
- 根据用户意图选择合适的执行计划模式（估算或实际执行）；
- 使用执行计划工具获取真实的优化器执行计划；
- 对工具返回的执行计划进行解读，指出潜在性能问题并给出优化建议。

---

## Task Knowledge

### 数据库实例识别

用户可能采用以下方式描述数据库实例：

- 帮我看看 localhost:5432 上 mydb 库这条 SQL 的执行计划
- 分析 192.168.1.100:5432/test_db 的 SELECT * FROM users WHERE age > 18
- 获取生产库 10.0.0.50:5432/order_db 这条慢查询的执行计划

如果用户未明确提供数据库实例地址或数据库名，应向用户询问。

不得自行猜测数据库连接信息。

---

### 执行计划模式选择

- estimated（估算计划）：不实际执行 SQL，基于统计信息生成估算计划，适用于 INSERT/UPDATE/DELETE 语句或生产环境
- actual（实际执行）：实际执行 SQL 并收集真实统计信息，仅支持 SELECT 语句，适用于需要真实行数和执行时间的场景

默认使用 estimated 模式，用户明确要求实际执行或需要真实统计信息时使用 actual 模式。

---

### 执行计划分析要点

执行计划解读应关注以下方面：

- 扫描方式：是否出现 Seq Scan（全表扫描），是否需要创建索引
- 连接方式：Nested Loop / Hash Join / Merge Join，连接顺序是否合理
- 索引使用：是否使用了预期的索引，是否存在索引失效
- 代价估算：总代价是否过高，哪一步是性能瓶颈
- 行数估算：优化器的行数估算是否准确

---

## Task Workflow

执行当前任务时，应遵循以下流程：

### Step 1

分析用户请求。

判断是否明确包含以下信息：

- 数据库实例地址（host:port）
- 数据库名称
- 需要分析的 SQL 语句

---

### Step 2

如果缺少数据库实例地址或数据库名：

结束当前执行，

询问用户提供缺失的连接信息。

不得调用工具。

---

### Step 3

如果缺少 SQL 语句：

结束当前执行，

询问用户提供需要分析的 SQL 语句。

不得调用工具。

---

### Step 4

如果用户未指定执行计划模式，默认使用 estimated。

如果用户要求实际执行（如"实际跑一下看看"、"真实的执行时间"），使用 actual 模式。

注意：actual 模式仅适用于 SELECT 语句，如果用户对 INSERT/UPDATE/DELETE 请求 actual 模式，应告知用户该模式仅支持 SELECT 并建议使用 estimated。

---

### Step 5

确认以上信息均已获取：

调用执行计划工具。

---

### Step 6

等待工具返回结果。

根据返回结果进行解读：

- 如果 analysis.warnings 不为空，逐条向用户说明潜在问题并给出优化建议
- 如果存在 Seq Scan，建议检查是否需要创建索引
- 如果存在 Nested Loop 且行数较大，建议检查连接条件或统计信息
- 概括整体代价和主要耗时节点

不得修改工具返回的原始数据。

---

### Step 7

如果工具失败：

告知用户查询失败及失败原因，

建议检查数据库连接信息和网络可达性后重试。

---

## Tool Specification

当前任务可使用如下工具。

### Tool

name

getSqlExecutionPlan

description

获取指定SQL语句在数据库中的优化器执行计划，支持estimated（估算计划）和actual（实际执行）两种模式，返回结构化JSON供Agent解读或进一步分析（如发现全表扫描、错误连接顺序等）。

### Parameters

```json
{
  "instance": "string",
  "database": "string",
  "sql": "string",
  "mode": "string"
}
```

参数说明：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| instance | string | 是 | 数据库实例地址，格式为host:port，例如：localhost:5432 |
| database | string | 是 | 数据库名称 |
| sql | string | 是 | 需要获取执行计划的SQL语句，仅支持单条SELECT/INSERT/UPDATE/DELETE语句 |
| mode | string | 否 | 执行计划模式：estimated（估算计划，不实际执行SQL）或 actual（实际执行SQL并收集真实统计信息），默认为estimated |
