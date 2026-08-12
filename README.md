# Agent —— 智能数据库运维 Agent

基于 **LangChain4j + Spring Boot 3** 构建的智能数据库运维 Agent，通过 **RAG（检索增强生成）** 与 **外部工具调用（ReAct）** 结合，实现数据库运维场景下的智能问答与自动化执行，降低人工运维成本，提升响应速度与准确性。

## 核心能力

- **RAG 智能问答**：从运维文档、故障案例、最佳实践等知识库中检索相关内容，辅助运维决策。
- **工具调用（ReAct）**：通过大模型规划并调用外部工具，直接执行数据库运维操作，例如：
  - 性能诊断与调优（健康巡检、性能指标、慢查询分析）
  - 故障排查（SQL 执行计划分析）
  - 告警通知（邮件告警）
- **多轮会话与记忆**：支持登录、会话管理、会话摘要与短期记忆，Agent 具备上下文连续性。
- **流式输出**：通过 SSE 实现对话流式返回。
- **混合检索与重排序**：向量检索 + 关键词检索 + 重排序，优化召回质量。
- **健康巡检**：定时采集数据库指标，LLM 判定异常并邮件告警，全程留痕可查。
- **全链路可观测性**：记录每次会话的 Trace、LLM 调用、工具调用明细与统计汇总。
- **检索评测**：内置 BEIR SciFact 数据集导入与检索评测接口，用于验证召回效果。

## 技术栈

| 分类 | 技术 |
| --- | --- |
| 语言 / 框架 | Java 17、Spring Boot 3.2.5、Maven |
| AI 编排 | LangChain4j 1.15.0（AI Services、Tool、MCP） |
| 大模型 | 阿里云百炼 Qwen（OpenAI 兼容接口），含 Chat / Embedding / Rerank 模型 |
| 数据库 | PostgreSQL（业务库 + 向量库，HNSW 索引）、MyBatis |
| 检索 | Elasticsearch（关键词检索）、pgvector 向量检索、重排序 |
| 存储 | MinIO（文档文件存储） |
| 消息 | RocketMQ（文档异步入库） |
| 缓存 | Redis（登录 Token、短期记忆） |
| 可观测性 | Micrometer Tracing + OpenTelemetry、Spring Boot Actuator |
| 其他 | Tika（文档解析）、Hutool、邮件发送、Spring Security Crypto |

## 项目结构

```
src/main/java/com/library/agent
├── AgentApplication.java            # 启动类
├── auth/                            # 登录鉴权（注册/登录/Token/用户上下文）
├── beir/                            # BEIR SciFact 检索评测（导入/检索）
├── config/                          # 配置类（MinIO、LangChain4j、CORS、健康检查）
├── context/                         # Agent 聊天上下文
├── controller/                      # 对外接口（聊天、RAG、健康巡检）
├── conversation/                    # 会话管理
├── dto/                             # 请求/响应对象
├── entity/                          # 数据库实体
├── enums/                           # 意图枚举
├── es/                              # Elasticsearch 关键词检索
├── healthcheck/                     # 健康巡检（指标采集/异常判定/邮件通知）
├── llm/                             # 大模型调用（意图识别/查询改写/工具调用）
├── mail/                            # 邮件服务
├── mapper/                          # MyBatis Mapper
├── memory/                          # 短期记忆与会话摘要
├── MQ/                              # RocketMQ 异步文档入库
├── observability/                   # 可观测性（Trace/LLM/工具调用记录）
├── rag/                             # RAG（文档解析/向量入库/检索）
├── service/                         # Agent 核心服务（意图路由、ReAct 编排）
├── tool/                            # 大模型可调用的工具集
├── tracing/                         # 全链路追踪
└── resources/
    ├── static/                      # 前端页面（聊天/可观测性/RAG 演示）
    └── Prompt/                      # 提示词工程（角色、路由、输出规则、任务模板）
```

## 快速开始

### 环境依赖

启动前需准备以下中间件与服务：

- **PostgreSQL**（含向量插件 pgvector，业务库 `rag_db`）
- **Redis**
- **Elasticsearch**（7.x / 8.x）
- **MinIO**
- **RocketMQ**（NameServer + Broker）
- **阿里云百炼平台** API Key（Chat / Embedding / Rerank 模型）

### 步骤

1. **初始化数据库**：在 `rag_db` 中执行建表语句（见各实体对应 SQL，例如 `src/main/resources/db/health_check_record.sql`，以及 `sql/index.sql` 创建向量 HNSW 索引）。

2. **配置**：复制 `src/main/resources/application-example.yaml` 为 `application.yaml`，填入实际的数据库、Redis、Elasticsearch、MinIO、RocketMQ 与百炼 API Key 等配置。

3. **启动**：

   ```bash
   ./mvnw spring-boot:run
   ```

   应用默认端口为 `8084`。

4. **访问**：
   - 聊天界面：`http://localhost:8084/index.html`
   - 可观测性面板：`http://localhost:8084/observability.html`
   - RAG 演示：`http://localhost:8084/rag.html`

## 主要接口

### 对话
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/agent/chat` | 发起 Agent 聊天（JSON 请求体，或兼容的 `query` / `conversationId` 参数） |
| POST | `/agent/chat/stream` | 聊天流式输出（SSE，`text/event-stream`） |

### 认证与会话
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/auth/register` | 注册 |
| POST | `/auth/login` | 登录（返回 Token） |
| POST | `/auth/logout` | 退出登录 |
| GET | `/auth/me` | 当前用户信息 |
| POST | `/agent/conversations` | 创建会话 |
| GET | `/agent/conversations` | 会话列表 |
| GET | `/agent/conversations/{id}` | 会话详情 |
| PATCH | `/agent/conversations/{id}/title` | 修改会话标题 |
| DELETE | `/agent/conversations/{id}` | 删除会话 |
| GET | `/agent/conversations/{id}/messages` | 会话历史消息 |

### 健康巡检
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/healthcheck/run` | 手动触发一轮巡检 |
| GET | `/healthcheck/records?limit=20` | 查询巡检记录 |

### 可观测性
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/agent/observability/traces` | 当前用户 Trace 列表 |
| GET | `/agent/observability/traces/{traceId}` | Trace 详情（含 LLM / 工具调用记录） |
| GET | `/agent/observability/stats` | 汇总统计 |

### 检索评测（BEIR SciFact）
| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/eval/beir/scifact/import-jsonl` | 从 JSONL 批量导入语料 |
| POST | `/eval/beir/scifact/import-document` | 导入单篇文档 |
| POST | `/eval/beir/scifact/search` | 检索 |

> 注：`/rag/ingest` 为旧版 RAG 上传接口，已废弃，文档入库现通过 RocketMQ 异步处理。

## 工具集

Agent 在 ReAct 循环中通过 LangChain4j `@Tool` 调用以下工具：

| 工具 | 说明 |
| --- | --- |
| `DatabaseHealthCheckTool` | 获取指定数据库实例的实时健康指标（活跃会话、缓冲池命中率、锁等待、死元组比例等） |
| `DatabaseMetricsTool` | 数据库性能指标查询 |
| `SlowQueryTool` | 慢查询分析 |
| `SqlExecutionPlanTool` | SQL 执行计划分析 |
| `EmailAlertTool` | 邮件告警通知 |
| `WeatherTool` | 天气查询（示例/演示工具） |

## 提示词工程

`src/main/resources/Prompt/` 下维护了 Agent 的提示词体系：

- `RoleAndObjectivePrompt.md` —— 角色与目标
- `CorePrinciplesPrompt.md` —— 核心原则
- `RoutePrompt.md` —— 意图路由
- `OutputRulesPrompt.md` —— 输出规则
- `CurrentConversationStatePrompt.md` —— 会话状态
- `task/` —— 各工具的任务模板（数据库指标、慢查询、执行计划、天气等）

## 开发规范

开发时请遵循 `CLAUDE.md` 中约定的代码规范：简洁去冗余、方法单一职责、使用 Java 风格多行注释（`/* ... */`）、注释详细且有意义。
