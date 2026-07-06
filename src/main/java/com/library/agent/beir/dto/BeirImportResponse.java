package com.library.agent.beir.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BeirImportResponse {

    private int total;

    private int imported;

    private int skipped;
}
