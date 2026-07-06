package com.library.agent.beir.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BeirSearchHit {

    private String beirDocId;

    private Long chunkId;

    private Long fileId;

    private Integer chunkIndex;

    private Integer rank;

    private Double score;
}
