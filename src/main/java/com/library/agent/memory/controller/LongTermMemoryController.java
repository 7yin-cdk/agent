package com.library.agent.memory.controller;

import com.library.agent.auth.context.UserContextHolder;
import com.library.agent.entity.AgentLongTermMemory;
import com.library.agent.enums.MemoryCategory;
import com.library.agent.memory.LongTermMemoryService;
import com.library.agent.memory.dto.LongTermMemoryCandidate;
import com.library.agent.memory.dto.MemoryAddRequest;
import com.library.agent.memory.dto.MemoryPageResponse;
import com.library.agent.memory.dto.MemoryUpdateRequest;
import com.library.agent.memory.dto.MemoryView;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/**
 * 长期记忆管理接口控制器。
 * <p>
 * 提供当前登录用户长期记忆的完整 CRUD 与召回调试能力。
 * userId 一律取自 {@link UserContextHolder}，全链路按用户隔离；
 * id 不存在或非本人 → 404，category 非法 → 400。
 */
@RestController
@RequestMapping("/agent/memories")
@RequiredArgsConstructor
public class LongTermMemoryController {

    private final LongTermMemoryService longTermMemoryService;

    /**
     * 分页列表，category / entity 可选过滤。
     */
    @GetMapping
    public MemoryPageResponse list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String entity,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Long userId = requireUserId();
        return longTermMemoryService.page(userId, requireValidCategoryOrNull(category), entity, page, size);
    }

    /**
     * 详情（剔除 embedding 大字段）。
     */
    @GetMapping("/{id}")
    public MemoryView detail(@PathVariable Long id) {
        Long userId = requireUserId();
        AgentLongTermMemory memory = longTermMemoryService.getById(userId, id);
        if (memory == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记忆不存在");
        }
        return toView(memory);
    }

    /**
     * 语义检索调试：query 向量召回，返回带相似度降序的记忆列表。
     */
    @GetMapping("/search")
    public List<MemoryView> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "10") int topK
    ) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query 不能为空");
        }
        return longTermMemoryService.search(requireUserId(), query.trim(), topK);
    }

    /**
     * 手动添加一条记忆（复用 store：自动 embedding / 去重冲突消解 / 容量淘汰）。
     */
    @PostMapping
    public MemoryView add(@RequestBody MemoryAddRequest request) {
        Long userId = requireUserId();
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        String category = requireValidCategory(request.getCategory());
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content 不能为空");
        }
        AgentLongTermMemory saved = longTermMemoryService.store(userId, toCandidate(request, category));
        if (saved == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保存失败");
        }
        return toView(saved);
    }

    /**
     * 修改一条记忆；content 变更会触发服务层重算 embedding。
     */
    @PatchMapping("/{id}")
    public MemoryView update(@PathVariable Long id, @RequestBody MemoryUpdateRequest request) {
        Long userId = requireUserId();
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        if (request.getCategory() != null) {
            requireValidCategory(request.getCategory());
        }
        AgentLongTermMemory updated = longTermMemoryService.update(userId, id, request);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记忆不存在");
        }
        return toView(updated);
    }

    /**
     * 逻辑删除单条记忆；不存在或非本人返回 404。
     */
    @DeleteMapping("/{id}")
    public String delete(@PathVariable Long id) {
        Long userId = requireUserId();
        if (longTermMemoryService.getById(userId, id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记忆不存在");
        }
        longTermMemoryService.deleteById(userId, id);
        return "delete success";
    }

    /**
     * 按分类逻辑删除；category 缺省表示删除当前用户全部长期记忆。
     */
    @DeleteMapping
    public String deleteByCategory(@RequestParam(required = false) String category) {
        Long userId = requireUserId();
        String normalized = requireValidCategoryOrNull(category);
        if (normalized == null) {
            longTermMemoryService.deleteByUser(userId);
        } else {
            longTermMemoryService.deleteByCategory(userId, normalized);
        }
        return "delete success";
    }

    /* ==================== 私有工具 ==================== */

    private Long requireUserId() {
        Long userId = UserContextHolder.getUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return userId;
    }

    /**
     * category 为空/缺省返回 null（表示不过滤）；非空则必须是合法分类，否则 400。
     */
    private String requireValidCategoryOrNull(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        return requireValidCategory(category);
    }

    /**
     * 校验并归一化分类（trim + 大写）；非法抛出 400。
     */
    private String requireValidCategory(String category) {
        if (category == null || category.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "category 不能为空");
        }
        String upper = category.trim().toUpperCase(Locale.ROOT);
        for (MemoryCategory value : MemoryCategory.values()) {
            if (value.name().equals(upper)) {
                return upper;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法分类: " + category);
    }

    private LongTermMemoryCandidate toCandidate(MemoryAddRequest request, String category) {
        LongTermMemoryCandidate candidate = new LongTermMemoryCandidate();
        candidate.setCategory(category);
        candidate.setContent(request.getContent().trim());
        candidate.setKeywords(request.getKeywords());
        candidate.setEntity(request.getEntity());
        candidate.setEntityType(request.getEntityType());
        candidate.setImportance(request.getImportance());
        candidate.setConfidence(request.getConfidence());
        candidate.setExpiredAt(request.getExpiredAt());
        return candidate;
    }

    /**
     * 实体 → 展示 VO：MemoryView 无 embedding 字段，BeanUtils 复制时自动剔除。
     */
    private MemoryView toView(AgentLongTermMemory memory) {
        MemoryView view = new MemoryView();
        BeanUtils.copyProperties(memory, view);
        return view;
    }
}
