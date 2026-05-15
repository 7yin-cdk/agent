package com.library.agent.rag.vector;

import java.util.List;

public interface VectorStoreService {

    /**
     * 将分块入库
     * @param chunks 分块
     */
    void addDocuments(List<String> chunks);

    /**
     * 查询相似度最高的k的分块
     * @param query 用户提问
     * @param topK k的取值
     * @return
     */
    List<String> similaritySearch(String query, int topK);
}
