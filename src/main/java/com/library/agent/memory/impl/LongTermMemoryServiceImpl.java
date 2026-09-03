package com.library.agent.memory.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.agent.config.LongTermMemoryProperties;
import com.library.agent.context.AgentChatContext;
import com.library.agent.entity.AgentLongTermMemory;
import com.library.agent.entity.AgentShortTermMemory;
import com.library.agent.enums.MemoryCategory;
import com.library.agent.llm.LlmService;
import com.library.agent.mapper.AgentLongTermMemoryMapper;
import com.library.agent.memory.LongTermMemoryService;
import com.library.agent.memory.dto.LongTermMemoryCandidate;
import com.library.agent.memory.dto.MemoryPageResponse;
import com.library.agent.memory.dto.MemoryUpdateRequest;
import com.library.agent.memory.dto.MemoryView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 长期记忆服务实现。
 * <p>
 * 抽取：postTurn 判显式"记住"走同步，否则按门槛转异步（经 self 代理避免自调用失效）。
 * 去重：dedup_key 唯一索引 + 冲突消解（MERGE/REPLACE/KEEP/DELETE）；EXPERIENCE 同问题不同结果保留双行。
 * 召回：always-on + 实体等值 + 向量，RRF 融合后再 rerank，写入 context。
 * 淘汰：写入即查容量、定时清扫物理清理过期/逻辑删除行。
 * 约定：所有对外入口 fail-open。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LongTermMemoryServiceImpl implements LongTermMemoryService {

    private static final int DEFAULT_IMPORTANCE = 5;
    private static final double DEFAULT_CONFIDENCE = 0.8;
    private static final int EMBED_DIMENSIONS = 1536;
    private static final int MAX_EXTRACT_MESSAGES = 40;
    private static final int MAX_DEDUP_KEY_LENGTH = 128;
    private static final int MAX_PAGE_SIZE = 100;
    private static final double RRF_K = 60.0;

    private static final Pattern REMEMBER_PATTERN =
            Pattern.compile("(?i)^\\s*(?:请记住|请记下|记住|记一下)\\b?");
    private static final Pattern ENTITY_SUFFIX_CN =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*(?:集群|数据库|实例|任务|索引|表|库)");
    private static final Pattern ENTITY_PREFIX_EN =
            Pattern.compile("(?i)\\b(?:db|database|cluster|table|schema)[.][A-Za-z0-9_.-]+");

    private final AgentLongTermMemoryMapper longTermMemoryMapper;
    private final LlmService llmService;
    private final ObjectMapper objectMapper;
    private final LongTermMemoryProperties properties;

    /* 自引用代理：postTurn 内部触发异步需跨代理调用，避免 @Async 自调用失效 */
    private final ObjectProvider<LongTermMemoryService> selfProvider;

    /* ==================== 对话后抽取 ==================== */

    @Override
    public void postTurn(Long userId, String conversationId, String userQuery,
                         String assistantAnswer, List<AgentShortTermMemory> turnMessages, String sourceTurn) {
        if (userId == null) {
            return;
        }
        try {
            if (isRememberCommand(userQuery)) {
                /* 显式"记住"：同步抽取入库，立即生效 */
                doExtractAndStore(userId, conversationId, userQuery, assistantAnswer, turnMessages, sourceTurn);
                return;
            }
            int minTurnMessages = properties.getExtraction().getMinTurnMessages();
            if (turnMessages == null || turnMessages.size() < minTurnMessages) {
                return;
            }
            /* 常规路径：异步抽取，不阻塞回答 */
            selfProvider.getObject().extractFromTurn(
                    userId, conversationId, userQuery, assistantAnswer, turnMessages, sourceTurn);
        } catch (Exception e) {
            log.warn("postTurn failed, userId={}, conversationId={}", userId, conversationId, e);
        }
    }

    @Async
    @Override
    public void extractFromTurn(Long userId, String conversationId, String userQuery,
                                String assistantAnswer, List<AgentShortTermMemory> turnMessages, String sourceTurn) {
        try {
            doExtractAndStore(userId, conversationId, userQuery, assistantAnswer, turnMessages, sourceTurn);
        } catch (Exception e) {
            log.warn("LTM async extract failed, userId={}, conversationId={}", userId, conversationId, e);
        }
    }

    private void doExtractAndStore(Long userId, String conversationId, String userQuery,
                                   String assistantAnswer, List<AgentShortTermMemory> turnMessages, String sourceTurn) {
        if (userId == null) {
            return;
        }
        String prompt = buildExtractPrompt(userQuery, assistantAnswer, turnMessages);
        if (prompt == null) {
            return;
        }
        String raw = llmService.chat(prompt);
        List<LongTermMemoryCandidate> candidates = parseCandidates(raw);
        if (candidates.isEmpty()) {
            return;
        }
        String uid = String.valueOf(userId);
        for (LongTermMemoryCandidate candidate : candidates) {
            try {
                saveCandidate(uid, candidate, conversationId, sourceTurn);
            } catch (Exception e) {
                log.warn("store extracted candidate failed, category={}", candidate.getCategory(), e);
            }
        }
    }

    /**
     * 构建抽取 prompt：要求 LLM 只输出 JSON 数组。
     * 恒先注入当前轮的 user 问题与 assistant 回答（设计 §5.1），再按容量去重追加同轮/历史消息，
     * 避免多轮会话中当前轮内容缺失导致漏抽。
     * 全部输入为空时返回 null（调用方不触发）。
     */
    private String buildExtractPrompt(String userQuery, String assistantAnswer,
                                      List<AgentShortTermMemory> turnMessages) {
        List<String> lines = new ArrayList<>();
        addExtractLine(lines, "user", userQuery);
        addExtractLine(lines, "assistant", assistantAnswer);
        if (turnMessages != null) {
            for (AgentShortTermMemory message : turnMessages) {
                if (lines.size() >= MAX_EXTRACT_MESSAGES) {
                    break;
                }
                addExtractLine(lines, normalizeRole(message.getRole()), message.getContent());
            }
        }
        if (lines.isEmpty()) {
            return null;
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("你是数据库运维 Agent 的长期记忆抽取器。\n");
        prompt.append("从下面这轮对话中抽取值得跨会话长期保存的记忆，只输出 JSON 数组。\n");
        prompt.append("只抽取五类：USER_PROFILE(用户角色/职责/负责的系统)、PREFERENCE(回答偏好)、CONSTRAINT(操作限制/禁止)、ENTITY(运维对象稳定事实)、EXPERIENCE(一次问题→方案→结果的经验，结果须含成功或失败)。\n");
        prompt.append("不抽取：寒暄、重复内容、纯临时指令、与运维无关的闲聊。\n");
        prompt.append("每条对象字段：{\"category\":\"...\",\"content\":\"自包含无指代的中文\",\"keywords\":[\"...\"],\"entity\":\"可选\",\"entity_type\":\"可选\",\"importance\":1-10整数,\"confidence\":0-1小数,\"dedup_key\":\"CATEGORY:归一化主题\"}。\n");
        prompt.append("要求：只输出 JSON 数组；不要 Markdown 代码块；不要任何多余说明；没有值得保存的内容则输出 []。\n\n");
        prompt.append("### 本轮对话\n");
        for (String line : lines) {
            prompt.append(line).append('\n');
        }
        prompt.append("\n### 输出\n[]");
        return prompt.toString();
    }

    private boolean isRememberCommand(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        return REMEMBER_PATTERN.matcher(query).find();
    }

    /* ==================== 入库与去重 ==================== */

    @Override
    public AgentLongTermMemory store(Long userId, LongTermMemoryCandidate candidate) {
        if (userId == null) {
            return null;
        }
        try {
            return saveCandidate(String.valueOf(userId), candidate, null, null);
        } catch (Exception e) {
            log.warn("store long-term memory failed, userId={}", userId, e);
            return null;
        }
    }

    @Override
    public List<AgentLongTermMemory> storeAll(Long userId, List<LongTermMemoryCandidate> candidates) {
        List<AgentLongTermMemory> stored = new ArrayList<>();
        if (userId == null || candidates == null) {
            return stored;
        }
        for (LongTermMemoryCandidate candidate : candidates) {
            AgentLongTermMemory saved = store(userId, candidate);
            if (saved != null) {
                stored.add(saved);
            }
        }
        return stored;
    }

    /**
     * 单条候选入库：去重键命中则冲突消解/合并，未命中则新插入并触发淘汰。
     */
    private AgentLongTermMemory saveCandidate(String uid, LongTermMemoryCandidate candidate,
                                              String conversationId, String sourceTurn) {
        if (uid == null || candidate == null) {
            return null;
        }
        String content = candidate.getContent() == null ? null : candidate.getContent().trim();
        if (content == null || content.isEmpty()) {
            return null;
        }
        String category = normalizeCategory(candidate.getCategory());
        if (category == null) {
            return null;
        }
        candidate.setContent(content);
        candidate.setCategory(category);
        int importance = clampImportance(candidate.getImportance() == null ? DEFAULT_IMPORTANCE : candidate.getImportance());
        double confidence = clampConfidence(candidate.getConfidence() == null ? DEFAULT_CONFIDENCE : candidate.getConfidence());
        List<String> keywords = cleanKeywords(candidate.getKeywords());

        String dedupKey = buildDedupKey(category, content, candidate.getDedupKey());
        AgentLongTermMemory old = mapperSelectByDedupKey(uid, dedupKey);

        if (old == null) {
            return insertNew(uid, candidate, category, content, keywords, importance, confidence, dedupKey,
                    conversationId, sourceTurn);
        }
        /* 同问题、不同结果的 EXPERIENCE 保留双行 */
        if (MemoryCategory.EXPERIENCE.name().equals(category)
                && !normalizeEquals(old.getContent(), content)) {
            String variantKey = nextVariantDedupKey(uid, dedupKey);
            return insertNew(uid, candidate, category, content, keywords, importance, confidence, variantKey,
                    conversationId, sourceTurn);
        }
        if (normalizeEquals(old.getContent(), content)) {
            /* 完全重复：仅更新重要度与访问计数，不新增行 */
            bumpExisting(old, uid, importance);
            return old;
        }
        return resolveAndApply(uid, old, candidate, content, keywords, importance, confidence);
    }

    private AgentLongTermMemory insertNew(String uid, LongTermMemoryCandidate candidate, String category,
                                          String content, List<String> keywords, int importance,
                                          double confidence, String dedupKey, String conversationId, String sourceTurn) {
        AgentLongTermMemory memory = new AgentLongTermMemory();
        memory.setUserId(uid);
        memory.setCategory(category);
        memory.setContent(content);
        memory.setKeywords(keywords);
        memory.setEntity(trimToNull(candidate.getEntity()));
        memory.setEntityType(trimToNull(candidate.getEntityType()));
        memory.setEmbedding(tryEmbed(content));
        memory.setImportance(importance);
        memory.setConfidence(confidence);
        memory.setDedupKey(dedupKey);
        memory.setAccessCount(0);
        memory.setSourceConversationId(conversationId);
        memory.setSourceTurn(sourceTurn);
        memory.setExpiredAt(candidate.getExpiredAt());
        memory.setMetadata(new HashMap<>());
        memory.setDeleted(false);
        LocalDateTime now = LocalDateTime.now();
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);
        longTermMemoryMapper.insert(memory);
        evictForCategory(uid, category);
        return memory;
    }

    private void bumpExisting(AgentLongTermMemory old, String uid, int importance) {
        Integer oldImportance = old.getImportance();
        if (oldImportance == null || importance > oldImportance) {
            longTermMemoryMapper.updateImportance(uid, old.getId(), importance);
        }
        longTermMemoryMapper.updateAccessCount(uid, old.getId());
    }

    /**
     * 冲突消解：交由 LLM 判断 MERGE/REPLACE/KEEP/DELETE 后落库。
     */
    private AgentLongTermMemory resolveAndApply(String uid, AgentLongTermMemory old,
                                                LongTermMemoryCandidate candidate, String content,
                                                List<String> keywords, int importance, double confidence) {
        JsonNode decision = llmResolveConflict(old, candidate);
        String action = decision == null ? "KEEP" : textOrNull(decision, "action");
        action = action == null ? "KEEP" : action.trim().toUpperCase(Locale.ROOT);

        switch (action) {
            case "MERGE":
            case "REPLACE":
                boolean replace = "REPLACE".equals(action);
                String merged = nonBlank(decision, "content") != null
                        ? decision.path("content").asText().trim()
                        : content;
                old.setContent(merged);
                old.setCategory(candidate.getCategory());
                old.setKeywords(replace ? keywords : mergeKeywords(old.getKeywords(), keywords));
                if (replace) {
                    old.setEntity(trimToNull(candidate.getEntity()));
                    old.setEntityType(trimToNull(candidate.getEntityType()));
                } else if (old.getEntity() == null) {
                    old.setEntity(trimToNull(candidate.getEntity()));
                }
                int finalImportance = decision.path("importance").isNumber()
                        ? clampImportance(decision.path("importance").asInt())
                        : (replace ? importance : Math.max(defaultImportance(old), importance));
                double finalConfidence = decision.path("confidence").isNumber()
                        ? clampConfidence(decision.path("confidence").asDouble())
                        : Math.max(defaultConfidence(old), confidence);
                old.setImportance(finalImportance);
                old.setConfidence(finalConfidence);
                old.setEmbedding(tryEmbed(merged));
                longTermMemoryMapper.updateContent(old);
                return old;
            case "DELETE":
                longTermMemoryMapper.logicalDeleteById(uid, old.getId());
                return old;
            default: /* KEEP */
                longTermMemoryMapper.updateAccessCount(uid, old.getId());
                return old;
        }
    }

    private JsonNode llmResolveConflict(AgentLongTermMemory old, LongTermMemoryCandidate candidate) {
        try {
            String prompt = new StringBuilder()
                    .append("两条记忆指向同一 dedup_key，请判断如何处理。\n")
                    .append("旧: ").append(old.getContent())
                    .append(" (").append(old.getCategory()).append(", importance=")
                    .append(defaultImportance(old)).append(")\n")
                    .append("新: ").append(candidate.getContent())
                    .append(" (").append(candidate.getCategory()).append(", importance=")
                    .append(defaultImportance(candidate)).append(")\n")
                    .append("- 互补/一致 → MERGE（合并成更完整表述）\n")
                    .append("- 新明确覆盖旧（数值/状态变化）→ REPLACE（以新为准）\n")
                    .append("- 新价值更低/无关 → KEEP（保留旧）\n")
                    .append("- 矛盾且无法判断 → REPLACE\n")
                    .append("只输出一行 JSON：{\"action\":\"MERGE|REPLACE|KEEP|DELETE\",\"content\":\"合并或最新表述\",\"importance\":n,\"confidence\":n}\n")
                    .toString();
            String raw = llmService.chat(prompt);
            String object = extractJsonObject(raw);
            if (object == null) {
                return null;
            }
            return objectMapper.readTree(object);
        } catch (Exception e) {
            log.warn("LTM conflict resolve failed, oldId={}", old == null ? null : old.getId(), e);
            return null;
        }
    }

    /* ==================== 召回 ==================== */

    @Override
    public void recall(AgentChatContext context) {
        if (context == null || context.getUserId() == null) {
            return;
        }
        try {
            doRecall(context);
        } catch (Exception e) {
            log.warn("LTM recall failed, userId={}", context.getUserId(), e);
        }
    }

    private void doRecall(AgentChatContext context) {
        String uid = String.valueOf(context.getUserId());
        String retrieval = pickRetrievalText(context);

        List<AgentLongTermMemory> alwaysOn = longTermMemoryMapper.selectAlwaysOn(uid, properties.getAlwaysOnLimit());
        List<AgentLongTermMemory> selected = new ArrayList<>(alwaysOn);

        /* 实体等值召回 */
        Set<String> tokens = extractEntityTokens(context.getQuery(), context.getRewrittenQuery());
        List<AgentLongTermMemory> entityHits = new ArrayList<>();
        if (!tokens.isEmpty()) {
            List<String> tokenList = new ArrayList<>(tokens);
            entityHits = longTermMemoryMapper.selectByEntities(uid, tokenList, tokenList,
                    properties.getEntityRecallLimit());
        }

        /* 向量召回（EXPERIENCE） */
        float[] vector = retrieval.isEmpty() ? null : tryEmbed(retrieval);
        List<AgentLongTermMemory> vectorHits = vector == null
                ? new ArrayList<>()
                : longTermMemoryMapper.selectTopKByEmbedding(uid, vector, properties.getVectorTopK());

        if (!entityHits.isEmpty() || !vectorHits.isEmpty()) {
            List<AgentLongTermMemory> fused = rrfMerge(entityHits, vectorHits, properties.getRrfLimit());
            List<AgentLongTermMemory> reranked = rerankMemories(retrieval, fused);
            appendUnique(selected, reranked);
        }

        if (selected.size() > properties.getInjectLimit()) {
            selected = new ArrayList<>(selected.subList(0, properties.getInjectLimit()));
        }
        context.setLongTermMemories(selected);
        for (AgentLongTermMemory memory : selected) {
            if (memory.getId() != null) {
                longTermMemoryMapper.updateAccessCount(uid, memory.getId());
            }
        }
    }

    private List<AgentLongTermMemory> rerankMemories(String retrieval, List<AgentLongTermMemory> fused) {
        if (retrieval.isEmpty() || fused.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> docs = new ArrayList<>();
        for (AgentLongTermMemory memory : fused) {
            docs.add(memory.getContent());
        }
        try {
            List<Integer> reranked = llmService.rerank(retrieval, docs,
                    properties.getRerankTopN(), properties.getRerankMinScore());
            List<AgentLongTermMemory> result = new ArrayList<>();
            for (Integer index : reranked) {
                if (index != null && index >= 0 && index < fused.size()) {
                    result.add(fused.get(index));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("LTM rerank failed, fallback to top fused", e);
            int fallback = Math.min(properties.getRerankTopN(), fused.size());
            return new ArrayList<>(fused.subList(0, fallback));
        }
    }

    /**
     * RRF 融合：与 RagServiceImpl 的 1/(60+rank) 打分一致，rank 从 1 起。
     */
    private List<AgentLongTermMemory> rrfMerge(List<AgentLongTermMemory> first,
                                               List<AgentLongTermMemory> second, int limit) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        addRrfScores(scores, first);
        addRrfScores(scores, second);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> findById(first, second, entry.getKey()))
                .toList();
    }

    private void addRrfScores(Map<Long, Double> scores, List<AgentLongTermMemory> memories) {
        for (int i = 0; i < memories.size(); i++) {
            Long id = memories.get(i).getId();
            if (id != null) {
                scores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            }
        }
    }

    private AgentLongTermMemory findById(List<AgentLongTermMemory> first, List<AgentLongTermMemory> second, Long id) {
        for (AgentLongTermMemory memory : first) {
            if (id.equals(memory.getId())) {
                return memory;
            }
        }
        for (AgentLongTermMemory memory : second) {
            if (id.equals(memory.getId())) {
                return memory;
            }
        }
        return null;
    }

    private Set<String> extractEntityTokens(String query, String rewrittenQuery) {
        Set<String> tokens = new LinkedHashSet<>();
        StringBuilder text = new StringBuilder();
        if (query != null) {
            text.append(query).append(' ');
        }
        if (rewrittenQuery != null) {
            text.append(rewrittenQuery);
        }
        Matcher cnMatcher = ENTITY_SUFFIX_CN.matcher(text);
        while (cnMatcher.find()) {
            addToken(tokens, cnMatcher.group());
        }
        Matcher enMatcher = ENTITY_PREFIX_EN.matcher(text);
        while (enMatcher.find()) {
            addToken(tokens, enMatcher.group());
        }
        return tokens;
    }

    private void addToken(Set<String> tokens, String raw) {
        String token = raw.trim();
        if (token.length() >= 3 && token.length() <= 60) {
            tokens.add(token);
        }
    }

    /* ==================== CRUD ==================== */

    @Override
    public MemoryPageResponse page(Long userId, String category, String entity, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        if (userId == null) {
            return new MemoryPageResponse(0, safePage, safeSize, new ArrayList<>());
        }
        String uid = String.valueOf(userId);
        long total = longTermMemoryMapper.countByUser(uid, trimToNull(category), trimToNull(entity));
        List<AgentLongTermMemory> rows = longTermMemoryMapper.selectPage(uid, trimToNull(category),
                trimToNull(entity), (safePage - 1) * safeSize, safeSize);
        List<MemoryView> items = new ArrayList<>();
        for (AgentLongTermMemory row : rows) {
            items.add(toView(row));
        }
        return new MemoryPageResponse(total, safePage, safeSize, items);
    }

    @Override
    public List<MemoryView> search(Long userId, String query, int topK) {
        List<MemoryView> views = new ArrayList<>();
        if (userId == null || query == null || query.isBlank()) {
            return views;
        }
        try {
            int safeTopK = Math.min(Math.max(topK, 1), MAX_PAGE_SIZE);
            float[] vector = tryEmbed(query.trim());
            if (vector == null) {
                return views;
            }
            List<AgentLongTermMemory> rows = longTermMemoryMapper.selectSearchByEmbedding(
                    String.valueOf(userId), vector, safeTopK);
            for (AgentLongTermMemory row : rows) {
                views.add(toView(row));
            }
        } catch (Exception e) {
            log.warn("LTM semantic search failed, userId={}", userId, e);
        }
        return views;
    }

    @Override
    public AgentLongTermMemory getById(Long userId, Long id) {
        if (userId == null || id == null) {
            return null;
        }
        return longTermMemoryMapper.selectById(String.valueOf(userId), id);
    }

    @Override
    public AgentLongTermMemory update(Long userId, Long id, MemoryUpdateRequest request) {
        if (userId == null || id == null || request == null) {
            return null;
        }
        String uid = String.valueOf(userId);
        AgentLongTermMemory memory = longTermMemoryMapper.selectById(uid, id);
        if (memory == null) {
            return null;
        }
        boolean contentChanged = false;
        if (request.getContent() != null && !request.getContent().equals(memory.getContent())) {
            memory.setContent(request.getContent().trim());
            contentChanged = true;
        }
        if (request.getCategory() != null) {
            String normalized = normalizeCategory(request.getCategory());
            if (normalized != null) {
                memory.setCategory(normalized);
            }
        }
        if (request.getKeywords() != null) {
            memory.setKeywords(cleanKeywords(request.getKeywords()));
        }
        if (request.getEntity() != null) {
            memory.setEntity(trimToNull(request.getEntity()));
        }
        if (request.getImportance() != null) {
            memory.setImportance(clampImportance(request.getImportance()));
        }
        if (request.getConfidence() != null) {
            memory.setConfidence(clampConfidence(request.getConfidence()));
        }
        if (request.getExpiredAt() != null) {
            memory.setExpiredAt(request.getExpiredAt());
        }
        if (contentChanged) {
            memory.setEmbedding(tryEmbed(memory.getContent()));
        }
        longTermMemoryMapper.updateContent(memory);
        return memory;
    }

    @Override
    public void deleteById(Long userId, Long id) {
        if (userId == null || id == null) {
            return;
        }
        longTermMemoryMapper.logicalDeleteById(String.valueOf(userId), id);
    }

    @Override
    public void deleteByCategory(Long userId, String category) {
        if (userId == null || category == null || category.isBlank()) {
            return;
        }
        longTermMemoryMapper.logicalDeleteByCategory(String.valueOf(userId), category.trim());
    }

    @Override
    public void deleteByUser(Long userId) {
        if (userId == null) {
            return;
        }
        longTermMemoryMapper.logicalDeleteByUser(String.valueOf(userId));
    }

    /* ==================== 淘汰 ==================== */

    @Override
    public void evictIfNeeded(Long userId, String category) {
        if (userId == null || category == null) {
            return;
        }
        try {
            evictForCategory(String.valueOf(userId), category);
        } catch (Exception e) {
            log.warn("LTM evict failed, userId={}, category={}", userId, category, e);
        }
    }

    private void evictForCategory(String uid, String category) {
        int capacity = properties.getCapacity().capacityFor(category);
        if (capacity <= 0) {
            return;
        }
        long count = longTermMemoryMapper.countByCategory(uid, category);
        int excess = (int) (count - capacity);
        if (excess <= 0) {
            return;
        }
        List<AgentLongTermMemory> victims = longTermMemoryMapper.selectEvictCandidates(uid, category, excess);
        if (victims == null || victims.isEmpty()) {
            return;
        }
        List<Long> ids = new ArrayList<>();
        for (AgentLongTermMemory victim : victims) {
            ids.add(victim.getId());
        }
        longTermMemoryMapper.physicalDeleteByIds(ids);
        log.info("LTM evicted {} memories, userId={}, category={}", ids.size(), uid, category);
    }

    @Scheduled(cron = "${agent.ltm.eviction.cron:0 17 3 * * *}")
    @Override
    public void scheduledEviction() {
        try {
            int deleted = longTermMemoryMapper.deleteExpired();
            if (deleted > 0) {
                log.info("LTM scheduled sweep physically deleted {} expired/logically-deleted rows", deleted);
            }
            List<AgentLongTermMemoryMapper.UserCategory> pairs = longTermMemoryMapper.selectUserCategories();
            for (AgentLongTermMemoryMapper.UserCategory pair : pairs) {
                try {
                    evictIfNeeded(Long.valueOf(pair.getUserId()), pair.getCategory());
                } catch (NumberFormatException ignore) {
                    /* 非数字 userId 跳过容量复查 */
                }
            }
        } catch (Exception e) {
            log.warn("LTM scheduled eviction failed", e);
        }
    }

    /* ==================== 工具方法 ==================== */

    private AgentLongTermMemory mapperSelectByDedupKey(String uid, String dedupKey) {
        return dedupKey == null ? null : longTermMemoryMapper.selectByDedupKey(uid, dedupKey);
    }

    private String nextVariantDedupKey(String uid, String baseKey) {
        String prefix = baseKey.length() > 120 ? baseKey.substring(0, 120) : baseKey;
        for (int i = 2; i < 100000; i++) {
            String key = prefix + "#" + i;
            if (longTermMemoryMapper.selectByDedupKey(uid, key) == null) {
                return key;
            }
        }
        return prefix + "#" + System.currentTimeMillis();
    }

    private String buildDedupKey(String category, String content, String given) {
        if (given != null && !given.isBlank()) {
            return cap(given.trim(), MAX_DEDUP_KEY_LENGTH);
        }
        String normalized = normalizeEqualsContent(content);
        String key = category + ":" + (normalized.length() > 96 ? normalized.substring(0, 96) : normalized);
        return cap(key, MAX_DEDUP_KEY_LENGTH);
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String upper = category.trim().toUpperCase(Locale.ROOT);
        for (MemoryCategory value : MemoryCategory.values()) {
            if (value.name().equals(upper)) {
                return upper;
            }
        }
        return null;
    }

    private String pickRetrievalText(AgentChatContext context) {
        String rewritten = context.getRewrittenQuery();
        if (rewritten != null && !rewritten.isBlank()) {
            return rewritten.trim();
        }
        return context.getQuery() == null ? "" : context.getQuery().trim();
    }

    private void appendUnique(List<AgentLongTermMemory> target, List<AgentLongTermMemory> source) {
        if (source == null) {
            return;
        }
        Set<Long> seen = new LinkedHashSet<>();
        for (AgentLongTermMemory memory : target) {
            if (memory.getId() != null) {
                seen.add(memory.getId());
            }
        }
        for (AgentLongTermMemory memory : source) {
            if (memory.getId() != null && seen.add(memory.getId())) {
                target.add(memory);
            }
        }
    }

    private float[] tryEmbed(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            List<Float> vector = llmService.embed(text.trim());
            if (vector == null || vector.size() != EMBED_DIMENSIONS) {
                return null;
            }
            float[] array = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                array[i] = vector.get(i);
            }
            return array;
        } catch (Exception e) {
            log.warn("LTM embed failed, degrade to null embedding: {}", e.getMessage());
            return null;
        }
    }

    private List<LongTermMemoryCandidate> parseCandidates(String raw) {
        List<LongTermMemoryCandidate> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        String content = raw.trim();
        if (content.startsWith("```")) {
            int firstNewline = content.indexOf('\n');
            content = firstNewline >= 0 ? content.substring(firstNewline + 1) : content;
            content = content.endsWith("```") ? content.substring(0, content.length() - 3) : content;
            content = content.trim();
        }
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return result;
        }
        try {
            JsonNode root = objectMapper.readTree(content.substring(start, end + 1));
            if (!root.isArray()) {
                return result;
            }
            for (JsonNode node : root) {
                if (!node.isObject()) {
                    continue;
                }
                LongTermMemoryCandidate candidate = new LongTermMemoryCandidate();
                candidate.setCategory(textOrNull(node, "category"));
                candidate.setContent(textOrNull(node, "content"));
                candidate.setKeywords(parseKeywords(node.get("keywords")));
                candidate.setEntity(textOrNull(node, "entity"));
                candidate.setEntityType(textOrNull(node, "entity_type"));
                if (node.path("importance").isNumber()) {
                    candidate.setImportance(node.path("importance").asInt());
                }
                if (node.path("confidence").isNumber()) {
                    candidate.setConfidence(node.path("confidence").asDouble());
                }
                candidate.setDedupKey(textOrNull(node, "dedup_key"));
                if (candidate.getContent() != null && !candidate.getContent().isBlank()
                        && normalizeCategory(candidate.getCategory()) != null) {
                    result.add(candidate);
                }
            }
        } catch (Exception e) {
            log.warn("LTM parse candidates failed: {}", e.getMessage());
        }
        return result;
    }

    private List<String> parseKeywords(JsonNode node) {
        List<String> keywords = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String keyword = item.isTextual() ? item.asText().trim() : "";
                if (!keyword.isEmpty()) {
                    keywords.add(keyword);
                }
            }
        }
        return keywords;
    }

    private List<String> cleanKeywords(List<String> keywords) {
        List<String> cleaned = new ArrayList<>();
        if (keywords == null) {
            return cleaned;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String keyword : keywords) {
            if (keyword == null) {
                continue;
            }
            String trimmed = keyword.trim();
            if (!trimmed.isEmpty() && seen.add(trimmed)) {
                cleaned.add(trimmed);
            }
        }
        return cleaned;
    }

    private List<String> mergeKeywords(List<String> original, List<String> added) {
        List<String> merged = cleanKeywords(original);
        Set<String> seen = new LinkedHashSet<>(merged);
        for (String keyword : added) {
            if (keyword != null && !keyword.isBlank() && seen.add(keyword.trim())) {
                merged.add(keyword.trim());
            }
        }
        return merged;
    }

    private String extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private String nonBlank(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private int clampImportance(int importance) {
        return Math.max(1, Math.min(10, importance));
    }

    private double clampConfidence(double confidence) {
        return Math.max(0.0, Math.min(1.0, confidence));
    }

    private int defaultImportance(AgentLongTermMemory memory) {
        return memory.getImportance() == null ? DEFAULT_IMPORTANCE : memory.getImportance();
    }

    private int defaultImportance(LongTermMemoryCandidate candidate) {
        return candidate.getImportance() == null ? DEFAULT_IMPORTANCE : candidate.getImportance();
    }

    private double defaultConfidence(AgentLongTermMemory memory) {
        return memory.getConfidence() == null ? DEFAULT_CONFIDENCE : memory.getConfidence();
    }

    private boolean normalizeEquals(String a, String b) {
        return a != null && b != null && normalizeEqualsContent(a).equals(normalizeEqualsContent(b));
    }

    private String normalizeEqualsContent(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String cap(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "unknown";
        }
        return role.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 追加一条抽取输入行：空内容或与已有行重复时跳过，避免当前轮与历史消息重复送入。
     */
    private void addExtractLine(List<String> lines, String role, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        String line = role + ": " + content.trim();
        if (!lines.contains(line)) {
            lines.add(line);
        }
    }

    private MemoryView toView(AgentLongTermMemory memory) {
        MemoryView view = new MemoryView();
        view.setId(memory.getId());
        view.setCategory(memory.getCategory());
        view.setContent(memory.getContent());
        view.setKeywords(memory.getKeywords());
        view.setEntity(memory.getEntity());
        view.setEntityType(memory.getEntityType());
        view.setImportance(memory.getImportance());
        view.setConfidence(memory.getConfidence());
        view.setDedupKey(memory.getDedupKey());
        view.setAccessCount(memory.getAccessCount());
        view.setLastAccessedAt(memory.getLastAccessedAt());
        view.setSourceConversationId(memory.getSourceConversationId());
        view.setSourceTurn(memory.getSourceTurn());
        view.setMetadata(memory.getMetadata());
        view.setExpiredAt(memory.getExpiredAt());
        view.setCreatedAt(memory.getCreatedAt());
        view.setUpdatedAt(memory.getUpdatedAt());
        view.setSimilarity(memory.getSimilarity());
        return view;
    }
}
