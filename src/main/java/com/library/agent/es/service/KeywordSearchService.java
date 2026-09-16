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

    /**
     * BEIR SciFact 评测语料的 file_id 前缀。
     * <p>
     * 评测文档由 {@code BeirScifactImportService.toEvalFileId} 生成为 -(docId+1) 的负数，
     * 真实 KB 文档的 file_id 来自 file_metadata.id 恒为正，故以 "-" 前缀区分。
     * 评测语料与 KB 文档共用同一索引，不过滤会挤占 KB 关键词召回的 topK 候选位。
     */
    private static final String EVAL_FILE_ID_PREFIX = "-";

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
                .withQuery(q -> q.bool(b -> b
                        .must(m -> m.match(mm -> mm.field("chunkText").query(queryText)))
                        .mustNot(n -> n.prefix(p -> p.field("fileId").value(EVAL_FILE_ID_PREFIX)))))
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
