package com.library.agent.mapper;

import com.library.agent.entity.TextChunk;
import com.library.agent.rag.dto.ChunkView;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TextChunkMapper {

    /**
     * 批量插入分片
     */
    void batchInsert(@Param("list") List<TextChunk> list);

    /**
     * 根据 chunkId 列表查询分片，并保持传入 ID 的顺序
     */
    List<TextChunk> selectByChunkIds(@Param("chunkIds") List<Long> chunkIds);

    /**
     * 按文件分页查询分片（携带源文档名）
     */
    List<ChunkView> selectByFileId(
            @Param("fileId") Long fileId,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    long countByFileId(@Param("fileId") Long fileId);

    /**
     * 按 chunkId 列表查询分片及源文档名，保持传入顺序。
     */
    List<ChunkView> selectByIdsWithFile(@Param("chunkIds") List<Long> chunkIds);

    int deleteByFileId(@Param("fileId") Long fileId);

}
