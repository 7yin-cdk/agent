/* Agent 长期记忆表 DDL。
   在 rag_db 数据库中执行一次即可。
   依赖 pgvector：CREATE EXTENSION IF NOT EXISTS vector;
   注意：本库 pgvector 不支持多列 HNSW，向量索引退化为单列 (embedding vector_cosine_ops)，
         user_id/category 由 WHERE 前置过滤（每用户数据量小，可接受）。 */

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS agent_long_term_memory (
    id                    BIGSERIAL PRIMARY KEY,
    user_id               VARCHAR(64)  NOT NULL,
    category              VARCHAR(32)  NOT NULL,               -- USER_PROFILE/PREFERENCE/CONSTRAINT/ENTITY/EXPERIENCE
    content               TEXT         NOT NULL,               -- 记忆正文：自包含、无指代
    keywords              TEXT[]       NOT NULL DEFAULT '{}',  -- 关键词，走 GIN 索引
    entity                VARCHAR(255),                        -- 实体名（集群/库/表/任务/实例等）
    entity_type           VARCHAR(64),
    embedding             VECTOR(1536),                        -- text-embedding-v4，与 FloatArrayTypeHandler 强校验一致
    importance            SMALLINT     NOT NULL DEFAULT 5 CHECK (importance BETWEEN 1 AND 10),
    confidence            DOUBLE PRECISION NOT NULL DEFAULT 0.8,
    dedup_key             VARCHAR(128),                        -- 去重键，NULL 表示不参与去重
    access_count          INTEGER      NOT NULL DEFAULT 0,
    last_accessed_at      TIMESTAMPTZ,
    source_conversation_id VARCHAR(64),                        -- 溯源：来源会话
    source_turn           VARCHAR(128),                        -- 溯源：来源轮次
    metadata              JSONB        NOT NULL DEFAULT '{}'::jsonb,  -- 扩展元数据（如 EXPERIENCE 的 result）
    is_deleted            BOOLEAN      NOT NULL DEFAULT FALSE, -- 逻辑删除标记
    expired_at            TIMESTAMPTZ,                         -- 显式过期时间
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

/* 去重：同一用户同一 dedup_key 仅保留一条未删除记录 */
CREATE UNIQUE INDEX IF NOT EXISTS uk_ltm_dedup
    ON agent_long_term_memory (user_id, dedup_key)
    WHERE dedup_key IS NOT NULL AND is_deleted = FALSE;

/* 常规查询：用户维度按分类、按重要度 */
CREATE INDEX IF NOT EXISTS idx_ltm_user_category
    ON agent_long_term_memory (user_id, category, importance DESC);
/* 最近更新列表 */
CREATE INDEX IF NOT EXISTS idx_ltm_user_updated
    ON agent_long_term_memory (user_id, updated_at DESC);
/* 实体等值过滤 */
CREATE INDEX IF NOT EXISTS idx_ltm_entity
    ON agent_long_term_memory (user_id, entity);
CREATE INDEX IF NOT EXISTS idx_ltm_entity_type
    ON agent_long_term_memory (user_id, entity_type);
/* 关键词重叠召回 */
CREATE INDEX IF NOT EXISTS idx_ltm_keywords
    ON agent_long_term_memory USING GIN (keywords);
/* 淘汰排序：低重要度 + 久未访问 + 低频优先 */
CREATE INDEX IF NOT EXISTS idx_ltm_evict
    ON agent_long_term_memory (user_id, category, importance ASC,
                               last_accessed_at ASC NULLS FIRST, access_count ASC)
    WHERE is_deleted = FALSE;
/* 向量召回（EXPERIENCE 语义召回）；单列 HNSW，user_id/category 由 WHERE 过滤 */
CREATE INDEX IF NOT EXISTS idx_ltm_embedding_hnsw
    ON agent_long_term_memory USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 200);
