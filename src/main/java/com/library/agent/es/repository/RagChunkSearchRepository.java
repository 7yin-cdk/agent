package com.library.agent.es.repository;

import com.library.agent.entity.RagChunkDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface RagChunkSearchRepository extends ElasticsearchRepository<RagChunkDocument, String> {
}