package com.library.agent.mapper;

import com.library.agent.entity.TextChunk;
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
}