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
public class BeirScifactChunkEvalController {

    private final BeirScifactChunkRetrievalService chunkRetrievalService;

    @PostMapping("/search-chunks")
    public BeirSearchResponse searchChunks(@RequestBody BeirSearchRequest request) {
        return chunkRetrievalService.search(request);
    }
}
