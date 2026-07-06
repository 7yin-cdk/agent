package com.library.agent.beir.dto;

import lombok.Data;

@Data
public class BeirSearchRequest {

    private String query;

    private Integer topK;

    private Integer vectorTopK;

    private Integer keywordTopK;

    private Integer candidateTopK;

    private Boolean useRerank;

    private Integer rerankTopN;

    private Double minRerankScore;
}
