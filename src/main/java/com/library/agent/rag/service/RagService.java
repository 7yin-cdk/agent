package com.library.agent.rag.service;

import org.springframework.web.multipart.MultipartFile;

public interface RagService {

    /**
     * 文档入库
     * @param file 原文件
     */
    void ingest(MultipartFile file);

    /**
     * 使用RAG回答用户提问
     * @param question 用户提问
     * @return 大模型回答
     */
    String query(String question);
}
