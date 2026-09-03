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
    private LocalDateTime createdAt;
}
