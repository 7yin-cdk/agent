/* RAG 分块来源元数据列 DDL。
   在 rag_db 数据库中执行一次即可（幂等，可重复执行）。
   用途：为 text_chunk 补充来源标题、来源地址与小节路径，使检索命中的片段能回溯到官方文档
        URL 与所属章节，支撑结果溯源、前端展示与按章节定位。
   依赖：无（仅追加列，不依赖 pgvector）。
   说明：仅对既有业务表 text_chunk 追加可空列并写列注释，不修改任何既有列的定义与数据；
        历史数据这三列保持 NULL，新入库的 markdown 文档才会写入取值，可安全执行。 */

ALTER TABLE text_chunk ADD COLUMN IF NOT EXISTS source_title VARCHAR(512);
ALTER TABLE text_chunk ADD COLUMN IF NOT EXISTS source_url   VARCHAR(1024);
ALTER TABLE text_chunk ADD COLUMN IF NOT EXISTS section_path VARCHAR(1024);

COMMENT ON COLUMN text_chunk.source_title IS '来源文档标题，取自 markdown frontmatter 的 title；非 markdown 文件为 NULL';
COMMENT ON COLUMN text_chunk.source_url IS '来源地址，取自 markdown frontmatter 的 source_url；非 markdown 文件为 NULL';
COMMENT ON COLUMN text_chunk.section_path IS '小节路径，形如 "VACUUM > Synopsis"，取自标题层级；非 markdown 文件为 NULL';
