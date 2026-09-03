package com.library.agent.rag.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档列表视图，附带切片数，用于前端列表展示。
 */
@Data
public class FileListVO {

    private Long id;
    private String fileName;
    private Long fileSize;
    private String contentType;
    private String status;
    private Long chunkCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
