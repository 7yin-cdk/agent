package com.library.agent.controller;

import com.library.agent.rag.service.RagService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 此接口已经废弃
 */
@RestController
public class RagController {

    @Autowired
    private RagService ragService;

    /**
     * 上传 RAG 文档并触发异步入库。
     */
    @PostMapping("/rag/ingest")
    public void ingest(@RequestParam("file")MultipartFile file){
        ragService.ingest(file);
    }

//    @PostMapping("/rag/query")
//    public String query(@RequestParam("question") String question){
//        return ragService.query(question);
//    }



}
