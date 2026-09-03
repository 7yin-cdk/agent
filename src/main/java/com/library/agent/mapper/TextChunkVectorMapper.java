package com.library.agent.mapper;

import com.library.agent.entity.TextChunkVector;
import com.library.agent.rag.dto.ChunkSimilarity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TextChunkVectorMapper {

    /**
     * 批量插入分片向量
     */
    void batchInsert(List<TextChunkVector> vectors);

    /**
     * 根据问题向量检索最相似的 topK 分片 ID
     */
    List<Long> selectTopKChunkIds(
            @Param("embedding") float[] embedding,
            @Param("topK") int topK
    );

    /**
     * 检索最相似的 topK 分片并返回余弦距离（用于检索调试展示相似度）。
     */
    List<ChunkSimilarity> selectTopKWithDistance(
            @Param("embedding") float[] embedding,
            @Param("topK") int topK
    );

    int deleteByFileId(@Param("fileId") Long fileId);

}