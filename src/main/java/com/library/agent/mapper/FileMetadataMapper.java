package com.library.agent.mapper;

import com.library.agent.entity.FileMetadata;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FileMetadataMapper {

    void insert(FileMetadata fileMetadata);

}