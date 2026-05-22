package com.library.agent.rag.service;

import com.library.agent.entity.AgentShortTermMemory;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * RAG 知识库服务。
 * <p>
 * 负责文档入库和基于知识库资料的问答，不直接管理会话创建和短期记忆读写。
 */
public interface RagService {

    /**
     * 将文档上传并提交到异步入库流程。
     *
     * @param file 原始文件
     */
    void ingest(MultipartFile file);

    /**
     * 使用 RAG 资料和当前会话历史回答用户问题。
     *
     * @param question 用户问题
     * @param historyMessages 当前会话历史消息
     * @return 大模型回答
     */
    String query(String question, List<AgentShortTermMemory> historyMessages);
}
