package com.library.agent.entity;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

@Data
@Document(indexName = "rag_chunks")
public class RagChunkDocument {

    @Id
    private String chunkId;

    @Field(type = FieldType.Keyword)
    private String fileId;

    @Field(type = FieldType.Integer)
    private Integer chunkIndex;

    @Field(type = FieldType.Text, analyzer = "standard")
    private String chunkText;
}