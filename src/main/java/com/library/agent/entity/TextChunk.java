package com.library.agent.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 文本分片实体
 */
@Data
public class TextChunk {

    /** 主键ID */
    private Long chunkId;

    /** 所属文件ID */
    private Long fileId;

    /** 分片序号 */
    private Integer chunkIndex;

    /** 分片文本 */
    private String chunkText;

    /** 分片长度 */
    private Integer chunkLength;

    /** 原文起始位置 */
    private Integer startOffset;

    /** 原文结束位置 */
    private Integer endOffset;

    /** 来源文档标题，取自 markdown frontmatter，非 markdown 文件为 null */
    private String sourceTitle;

    /** 来源地址，取自 markdown frontmatter 的 source_url，非 markdown 文件为 null */
    private String sourceUrl;

    /** 小节路径，形如 "VACUUM > Synopsis"，非 markdown 文件为 null */
    private String sectionPath;

    /** 状态 INIT / EMBEDDED */
    private String status;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;
}