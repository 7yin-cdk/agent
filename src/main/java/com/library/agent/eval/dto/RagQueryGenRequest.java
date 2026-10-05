package com.library.agent.eval.dto;

import lombok.Data;

/**
 * 由文档片段反向生成测试 query 的请求。
 * <p>
 * 用于构造 RAG 测评的 qrels：拿到一段已入库的 chunk，让模型生成若干「真实运维人员会用来检索它的提问」，
 * 再人工复核后作为测试集。
 */
@Data
public class RagQueryGenRequest {

    /**
     * 文档名，仅用于给模型提供上下文
     */
    private String fileName;

    /**
     * 所属小节路径，用于在提示词中提醒模型「不要照抄这里」
     */
    private String sectionPath;

    /**
     * 片段正文；服务端会自动剥掉正文头部的面包屑前缀
     */
    private String chunkText;
}
