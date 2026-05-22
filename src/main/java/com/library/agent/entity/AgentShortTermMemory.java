package com.library.agent.entity;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Agent 短期记忆实体类
 * 对应 PostgreSQL 表 agent_short_term_memory
 */
@Data
public class AgentShortTermMemory {

    /**
     * 主键 ID
     */
    private Long id;

    /**
     * 用户 ID，用于隔离不同用户的会话记忆
     */
    private String userId;

    /**
     * 会话 ID，同一个会话下的消息共同组成短期记忆
     */
    private String conversationId;

    /**
     * 消息角色：system、user、assistant、tool
     */
    private String role;

    /**
     * 消息正文内容
     */
    private String content;

    /**
     * 消息大致 token 数量，用于控制上下文窗口长度
     */
    private Integer tokenCount;

    /**
     * 消息顺序号，用于稳定排序
     */
    private Long messageOrder;

    /**
     * 扩展元数据，例如模型名、工具名、请求 ID、意图类型等
     */
    private Map<String, Object> metadata;

    /**
     * 逻辑删除标记
     */
    private Boolean deleted;

    /**
     * 过期时间，超过后可清理或不再作为上下文使用
     */
    private LocalDateTime expiredAt;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
