package com.library.agent.tracing;

/**
 * 全链路追踪 Span 名称常量。
 * <p>
 * 统一管理所有 Span 名称，方便在 Jaeger 中按操作名称筛选。
 */
public final class TracingConstant {

    private TracingConstant() {
    }

    /* ==================== HTTP / 入口层 ==================== */

    /** HTTP 请求入口 Span 名称前缀 */
    public static final String HTTP_PREFIX = "http";

    /* ==================== Agent 编排层 ==================== */

    public static final String AGENT_CHAT = "agent.chat";
    public static final String AGENT_CHAT_STREAM = "agent.chat-stream";
    public static final String AGENT_INTENT_IDENTIFY = "agent.intent-identify";
    public static final String AGENT_ROUTE = "agent.route";
    public static final String QUERY_REWRITE = "query.rewrite";

    /* ==================== LLM 调用 ==================== */

    public static final String LLM_DEEPSEEK_CHAT = "llm.deepseek.chat";
    public static final String LLM_DEEPSEEK_CHAT_STREAM = "llm.deepseek.chat-stream";
    public static final String LLM_BAILIAN_EMBED = "llm.bailian.embed";
    public static final String LLM_BAILIAN_RERANK = "llm.bailian.rerank";

    /* ==================== ReAct 循环 ==================== */

    public static final String REACT_STEP_PREFIX = "react.step";
    public static final String REACT_LLM_CALL = "react.llm-call";
    public static final String REACT_TOOL_PREFIX = "react.tool";

    /* ==================== RAG 管道 ==================== */

    public static final String RAG_EMBED_QUERY = "rag.embed-query";
    public static final String RAG_VECTOR_SEARCH = "rag.vector-search";
    public static final String RAG_KEYWORD_SEARCH = "rag.keyword-search";
    public static final String RAG_RRF_MERGE = "rag.rrf-merge";
    public static final String RAG_RERANK = "rag.rerank";
    public static final String RAG_BUILD_PROMPT = "rag.build-prompt";

    /* ==================== 异步处理 ==================== */

    public static final String ASYNC_SUMMARY = "async.summary";
    public static final String RAG_ASYNC_PROCESS = "rag.async.process";
    public static final String RAG_ASYNC_PARSE = "rag.async.parse";
    public static final String RAG_ASYNC_CHUNK = "rag.async.chunk";
    public static final String RAG_ASYNC_EMBED = "rag.async.embed";
    public static final String RAG_ASYNC_SAVE = "rag.async.save";

    /* ==================== 消息队列 ==================== */

    public static final String MQ_PRODUCE = "mq.produce";
    public static final String MQ_CONSUME = "mq.consume";

    /* ==================== 标签 Key ==================== */

    public static final String TAG_DB_SYSTEM = "db.system";
    public static final String TAG_DB_OPERATION = "db.operation";
    public static final String TAG_DB_STATEMENT = "db.statement";
    public static final String TAG_DB_PARAMS = "db.params";
    public static final String TAG_DB_ROWS_AFFECTED = "db.rows_affected";
    public static final String TAG_DB_MAPPER = "db.mapper";
    public static final String TAG_USER_ID = "userId";
    public static final String TAG_CONVERSATION_ID = "conversationId";
    public static final String TAG_INTENT_TYPE = "intentType";
    public static final String TAG_MODEL = "gen_ai.request.model";
    public static final String TAG_PROMPT_LENGTH = "gen_ai.prompt.length";
    public static final String TAG_RESPONSE_LENGTH = "gen_ai.response.length";
    public static final String TAG_DURATION_MS = "duration_ms";
    public static final String TAG_ERROR = "error";
    public static final String TAG_SPAN_KIND = "span.kind";
}
