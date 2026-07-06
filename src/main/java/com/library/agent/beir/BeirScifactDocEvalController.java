package com.library.agent.beir;

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
public class BeirScifactDocEvalController {

    private final BeirScifactDocRetrievalService docRetrievalService;

    @PostMapping("/search-docs")
    public BeirSearchResponse searchDocs(@RequestBody BeirSearchRequest request) {
        return docRetrievalService.search(request);
    }
}
