package com.library.agent.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件元数据表实体类
 * 用于管理上传文件及其在MinIO中的存储信息
 */
@Data
public class FileMetadata {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 原始文件名称
     */
    private String fileName;

    /**
     * 文件访问URL（MinIO访问地址）
     */
    private String fileUrl;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 文件类型（MIME类型，例如application/pdf）
     */
    private String contentType;

    /**
     * MinIO桶名称
     */
    private String bucketName;

    /**
     * MinIO中对象名称（唯一标识）
     */
    private String objectName;

    /**
     * 文件处理状态（UPLOADED-已上传，PARSED-已解析，EMBEDDED-已向量化）
     */
    private String status;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}