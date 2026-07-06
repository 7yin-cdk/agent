package com.library.agent.beir.dto;

import lombok.Data;

@Data
public class BeirImportRequest {

    private String corpusJsonlPath;

    private Integer limit;
}
