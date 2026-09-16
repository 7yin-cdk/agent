package com.library.agent.rag.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分片视图：携带源文档名，用于前端查看某文件的切片。
 */
@Data
public class ChunkView {

    private Long chunkId;
    private Long fileId;
    private String fileName;
    private Integer chunkIndex;
    private String chunkText;
    private Integer chunkLength;

    /** 小节路径，形如 "VACUUM > Synopsis"，仅 markdown 文档有值 */
    private String sectionPath;

    /** 来源地址，仅 markdown 文档有值 */
    private String sourceUrl;

    private LocalDateTime createdAt;
}
