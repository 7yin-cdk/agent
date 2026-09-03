package com.library.agent.rag.controller;

import com.library.agent.entity.FileMetadata;
import com.library.agent.mapper.FileMetadataMapper;
import com.library.agent.mapper.TextChunkMapper;
import com.library.agent.rag.dto.ChunkView;
import com.library.agent.rag.dto.FileListVO;
import com.library.agent.rag.dto.PageResult;
import com.library.agent.rag.dto.RetrievedChunk;
import com.library.agent.rag.dto.RetrieveRequest;
import com.library.agent.rag.service.KbRetrievalService;
import com.library.agent.rag.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 知识库管理控制器。
 * <p>
 * 知识库无 userId 维度（全局限登录可见）：提供文档分页/上传/删除、切片分页查看，
 * 以及复刻生产 RAG 召回管线的检索调试接口。上传与级联删除委托 {@link RagService}，
 * 检索调试委托 {@link KbRetrievalService}。
 */
@RestController
@RequestMapping("/agent/kb")
@RequiredArgsConstructor
public class KbController {

    private final FileMetadataMapper fileMetadataMapper;
    private final TextChunkMapper textChunkMapper;
    private final RagService ragService;
    private final KbRetrievalService kbRetrievalService;

    /**
     * 文档分页：keyword 按文件名模糊过滤，status 精确过滤，按 id 倒序。
     */
    @GetMapping("/documents")
    public PageResult<FileListVO> documents(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        int current = Math.max(page, 1);
        int limit = Math.min(Math.max(size, 1), 100);
        String kw = blankToNull(keyword);
        String st = blankToNull(status);
        long total = fileMetadataMapper.countPage(kw, st);
        List<FileListVO> items = fileMetadataMapper.selectPage(kw, st, (current - 1) * limit, limit);
        return PageResult.of(total, current, limit, items);
    }

    /**
     * 上传文档：MinIO 存原文 → file_metadata(UPLOADED) → 发异步入库消息。
     */
    @PostMapping("/documents")
    public FileMetadata upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件不能为空");
        }
        return ragService.uploadAndEnqueue(file);
    }

    /**
     * 查看某文档切片，按 chunk_index 升序分页。
     */
    @GetMapping("/documents/{fileId}/chunks")
    public PageResult<ChunkView> chunks(
            @PathVariable Long fileId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        requireFile(fileId);
        int current = Math.max(page, 1);
        int limit = Math.min(Math.max(size, 1), 200);
        long total = textChunkMapper.countByFileId(fileId);
        List<ChunkView> items = textChunkMapper.selectByFileId(fileId, (current - 1) * limit, limit);
        return PageResult.of(total, current, limit, items);
    }

    /**
     * 级联删除文档（Postgres 三表 + ES/MinIO 尽力清理）。
     */
    @DeleteMapping("/documents/{fileId}")
    public String delete(@PathVariable Long fileId) {
        requireFile(fileId);
        ragService.deleteDocument(fileId);
        return "delete success";
    }

    /**
     * 检索调试：query 向量 + 关键词多路召回，可选 rerank / 按文件过滤。
     */
    @PostMapping("/retrieve")
    public List<RetrievedChunk> retrieve(@RequestBody RetrieveRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query 不能为空");
        }
        return kbRetrievalService.retrieve(
                request.getQuery().trim(),
                request.getTopK(),
                request.getRerank(),
                request.getFileId()
        );
    }

    private void requireFile(Long fileId) {
        if (fileMetadataMapper.selectById(fileId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在: " + fileId);
        }
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
