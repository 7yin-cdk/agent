package com.library.agent.eval.controller;

import com.library.agent.auth.context.UserContextHolder;
import com.library.agent.eval.dto.RagQueryGenRequest;
import com.library.agent.eval.dto.RagQueryGenResponse;
import com.library.agent.eval.dto.RagRetrieveRequest;
import com.library.agent.eval.dto.RagRetrieveResponse;
import com.library.agent.eval.service.RagQueryGenService;
import com.library.agent.rag.dto.RetrievalStageTrace;
import com.library.agent.rag.service.KbRetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索测评接口。
 * <p>
 * 与对话类测评（CHAT / TOOL）不同，这里不经过意图识别与生成链路，直接驱动检索模块本身，
 * 按 query 取回各阶段的有序 chunkId，用于计算双路召回率、rerank 前后排名与整体召回率。
 * 因此本接口的口径是「检索模块能力口径」，不等同于线上 RAG 的端到端效果。
 */
@RestController
@RequestMapping("/eval/rag")
@RequiredArgsConstructor
public class RagEvalController {

    private final KbRetrievalService kbRetrievalService;
    private final RagQueryGenService ragQueryGenService;

    /**
     * 单条检索测评。
     */
    @PostMapping("/retrieve")
    public RagRetrieveResponse retrieve(@RequestBody RagRetrieveRequest request) {
        requireUserId();
        return doRetrieve(request);
    }

    /**
     * 批量检索测评，降低评测脚本的往返次数。
     */
    @PostMapping("/retrieve-batch")
    public List<RagRetrieveResponse> retrieveBatch(@RequestBody List<RagRetrieveRequest> requests) {
        requireUserId();
        List<RagRetrieveResponse> responses = new ArrayList<>();
        for (RagRetrieveRequest request : requests) {
            responses.add(doRetrieve(request));
        }
        return responses;
    }

    /**
     * 由文档片段反向生成候选测试 query，供人工复核后冻结为测试集。
     */
    @PostMapping("/gen-query")
    public RagQueryGenResponse genQuery(@RequestBody RagQueryGenRequest request) {
        requireUserId();
        return ragQueryGenService.generate(request);
    }

    /**
     * 查询当前生效的出题提示词版本；评测脚本据此写入批次元信息，避免版本号在两处硬编码。
     */
    @GetMapping("/gen-query/version")
    public Map<String, Object> genQueryVersion() {
        requireUserId();
        return Map.of("promptVersion", RagQueryGenService.QUERY_GEN_PROMPT_VERSION);
    }

    /**
     * 复用同一份检索实现，不拷贝检索算法：收集器在这里新建并回填进响应。
     */
    private RagRetrieveResponse doRetrieve(RagRetrieveRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "检索 query 不能为空");
        }
        RetrievalStageTrace stage = new RetrievalStageTrace();
        RagRetrieveResponse response = new RagRetrieveResponse();
        response.setQuery(request.getQuery());
        response.setHits(kbRetrievalService.retrieve(request.getQuery(), request.getTopK(),
                request.getRerank(), request.getFileId(), stage));
        response.setStage(stage);
        return response;
    }

    private Long requireUserId() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return userId;
    }
}
