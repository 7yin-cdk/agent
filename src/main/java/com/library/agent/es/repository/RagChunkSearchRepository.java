package com.library.agent.es.repository;

import com.library.agent.entity.RagChunkDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface RagChunkSearchRepository extends ElasticsearchRepository<RagChunkDocument, String> {

    /**
     * 查询某文件在 ES 中的全部关键词分片（fileId 存储为 keyword 字符串）。
     */
    List<RagChunkDocument> findByFileId(String fileId);
}