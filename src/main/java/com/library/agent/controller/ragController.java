package com.library.agent.controller;

import com.library.agent.rag.service.RagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class ragController {

    @Autowired
    private RagService ragService;

    @PostMapping("/rag/ingest")
    public void ingest(@RequestParam("file")MultipartFile file){
        ragService.ingest(file);
    }

}
