package com.library.agent.mapper;

import com.library.agent.entity.FileMetadata;
import com.library.agent.rag.dto.FileListVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface FileMetadataMapper {

    void insert(FileMetadata fileMetadata);

    FileMetadata selectById(@Param("id") Long id);

    List<FileListVO> selectPage(
            @Param("keyword") String keyword,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    long countPage(@Param("keyword") String keyword, @Param("status") String status);

    int updateStatus(@Param("id") Long id, @Param("status") String status);

    int deleteById(@Param("id") Long id);

}
