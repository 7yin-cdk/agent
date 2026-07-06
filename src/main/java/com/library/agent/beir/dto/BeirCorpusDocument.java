package com.library.agent.beir.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
public class BeirCorpusDocument {

    @JsonAlias({"doc_id", "_id"})
    private String docId;

    private String title;

    private String text;

    private String content;
}
