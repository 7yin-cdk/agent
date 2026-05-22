package com.library.agent.mapper;

import com.library.agent.entity.TextChunkVector;
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
}