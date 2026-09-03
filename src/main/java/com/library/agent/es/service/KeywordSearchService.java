package com.library.agent.es.service;
import com.library.agent.entity.RagChunkDocument;
import com.library.agent.entity.TextChunk;
import com.library.agent.es.repository.RagChunkSearchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class KeywordSearchService {

    private final RagChunkSearchRepository repository;
    private final ElasticsearchOperations elasticsearchOperations;

    public void indexChunks(List<TextChunk> chunks) {
        List<RagChunkDocument> documents = chunks.stream().map(chunk -> {
            RagChunkDocument document = new RagChunkDocument();
            document.setChunkId(String.valueOf(chunk.getChunkId()));
            document.setFileId(String.valueOf(chunk.getFileId()));
            document.setChunkIndex(chunk.getChunkIndex());
            document.setChunkText(chunk.getChunkText());
            return document;
        }).toList();

        repository.saveAll(documents);
    }

    public List<Long> searchChunkIds(String queryText, int topK) {
        NativeQuery query = NativeQuery.builder()
                .withQuery(q -> q.match(m -> m.field("chunkText").query(queryText)))
                .withPageable(PageRequest.of(0, topK))
                .build();

        return elasticsearchOperations.search(query, RagChunkDocument.class)
                .stream()
                .map(hit -> Long.valueOf(hit.getContent().getChunkId()))
                .toList();
    }

    /**
     * 删除某文件在 ES 中的全部关键词分片。
     */
    public void deleteByFileId(Long fileId) {
        repository.deleteAll(repository.findByFileId(String.valueOf(fileId)));
    }
}
