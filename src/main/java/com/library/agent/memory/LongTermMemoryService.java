package com.library.agent.memory;

import com.library.agent.context.AgentChatContext;
import com.library.agent.entity.AgentLongTermMemory;
import com.library.agent.entity.AgentShortTermMemory;
import com.library.agent.memory.dto.LongTermMemoryCandidate;
import com.library.agent.memory.dto.MemoryPageResponse;
import com.library.agent.memory.dto.MemoryUpdateRequest;
import com.library.agent.memory.dto.MemoryView;

import java.util.List;

/**
 * 长期记忆服务：负责抽取、去重/冲突消解、召回、淘汰与 CRUD。
 * <p>
 * 约定：所有对外入口 fail-open，任何异常不得抛给主流程；
 * embedding 失败降级为 NULL 存储，仍可参与 always-on 与实体等值召回。
 */
public interface LongTermMemoryService {

    /**
     * 每轮对话结束调用：判断显式"记住"命令走同步抽取入库，否则按门槛转发异步抽取。
     *
     * @param userId          当前用户 ID
     * @param conversationId  会话 ID
     * @param userQuery       用户本轮问题
     * @param assistantAnswer 助手本轮回答
     * @param turnMessages    本轮内产生或参与的消息（含 user/assistant/tool）
     * @param sourceTurn      溯源轮次标记，可为 null
     */
    void postTurn(Long userId, String conversationId, String userQuery,
                  String assistantAnswer, List<AgentShortTermMemory> turnMessages, String sourceTurn);

    /**
     * 异步抽取：把本轮对话交给 LLM 产出候选记忆并入库（@Async，见实现类）。
     */
    void extractFromTurn(Long userId, String conversationId, String userQuery,
                         String assistantAnswer, List<AgentShortTermMemory> turnMessages, String sourceTurn);

    /**
     * 入库一条候选记忆（手动添加/显式记住共用），自动 embedding、去重、淘汰。
     *
     * @return 落库或合并后的记忆；候选非法或异常时为 null
     */
    AgentLongTermMemory store(Long userId, LongTermMemoryCandidate candidate);

    /**
     * 批量入库候选记忆。
     */
    List<AgentLongTermMemory> storeAll(Long userId, List<LongTermMemoryCandidate> candidates);

    /**
     * 回答前召回：四路取（always-on/实体/向量）→ RRF → rerank，写入 context.longTermMemories。
     * 整体 fail-open。
     */
    void recall(AgentChatContext context);

    /**
     * 管理分页查询（category/entity 可选过滤）。
     */
    MemoryPageResponse page(Long userId, String category, String entity, int page, int size);

    /**
     * 语义检索调试：按 query 向量检索该用户全部长期记忆，按相似度降序返回 topK 条。
     * 只读，不更新访问计数；query 为空或 embedding 失败时返回空列表。
     */
    java.util.List<MemoryView> search(Long userId, String query, int topK);

    /**
     * 按用户 + id 查详情，不存在或非本人返回 null。
     */
    AgentLongTermMemory getById(Long userId, Long id);

    /**
     * 修改记忆，返回更新后实体；不存在返回 null。
     */
    AgentLongTermMemory update(Long userId, Long id, MemoryUpdateRequest request);

    /**
     * 逻辑删除单条。
     */
    void deleteById(Long userId, Long id);

    /**
     * 逻辑删除某用户某分类记忆。
     */
    void deleteByCategory(Long userId, String category);

    /**
     * 逻辑删除某用户全部长期记忆。
     */
    void deleteByUser(Long userId);

    /**
     * 写入后容量淘汰：超过该分类上限时按重要度×最近访问淘汰。
     */
    void evictIfNeeded(Long userId, String category);

    /**
     * 定时清扫：物理清理过期/逻辑删除行，并做全用户全分类容量复查（@Scheduled）。
     */
    void scheduledEviction();
}
