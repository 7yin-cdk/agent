/* 健康巡检记录表 DDL，在 rag_db 数据库中执行一次即可 */

CREATE TABLE IF NOT EXISTS health_check_record (
    id                BIGSERIAL PRIMARY KEY,
    run_id            VARCHAR(64)  NOT NULL,
    instance_name     VARCHAR(100) NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    abnormal_metrics  TEXT,
    llm_summary       TEXT,
    email_sent        BOOLEAN      DEFAULT FALSE,
    recipients        TEXT,
    checked_at        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    created_at        TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON COLUMN health_check_record.status IS '巡检状态：NORMAL 正常 / ANOMALY 异常 / ERROR 采集失败';
