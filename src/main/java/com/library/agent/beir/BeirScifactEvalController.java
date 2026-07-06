package com.library.agent.beir;

import com.library.agent.beir.dto.BeirCorpusDocument;
import com.library.agent.beir.dto.BeirImportRequest;
import com.library.agent.beir.dto.BeirImportResponse;
import com.library.agent.beir.dto.BeirSearchRequest;
import com.library.agent.beir.dto.BeirSearchResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/eval/beir/scifact")
@RequiredArgsConstructor
public class BeirScifactEvalController {

    private final BeirScifactImportService importService;
    private final BeirScifactRetrievalService retrievalService;

    @PostMapping("/import-jsonl")
    public BeirImportResponse importJsonl(@RequestBody BeirImportRequest request) {
        return importService.importJsonl(request.getCorpusJsonlPath(), request.getLimit());
    }

    @PostMapping("/import-document")
    public boolean importDocument(@RequestBody BeirCorpusDocument document) {
        return importService.importDocument(document);
    }

    @PostMapping("/search")
    public BeirSearchResponse search(@RequestBody BeirSearchRequest request) {
        return retrievalService.search(request);
    }
}
