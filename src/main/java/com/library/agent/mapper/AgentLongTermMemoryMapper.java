package com.library.agent.mapper;

import com.library.agent.entity.AgentLongTermMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Agent 长期记忆表 Mapper。
 * 覆盖：基础插入/详情/去重查询、管理分页、always-on、实体等值、向量召回、访问计数、更新、淘汰与删除。
 */
@Mapper
public interface AgentLongTermMemoryMapper {

    /**
     * 插入一条长期记忆。
     */
    int insert(AgentLongTermMemory memory);

    /**
     * 按用户 + 主键查询未删除的长期记忆。
     */
    AgentLongTermMemory selectById(@Param("userId") String userId, @Param("id") Long id);

    /**
     * 按去重键查询（用于冲突消解），不排除已过期但未删除的行，避免重复入库命中唯一索引。
     */
    AgentLongTermMemory selectByDedupKey(@Param("userId") String userId, @Param("dedupKey") String dedupKey);

    /**
     * 管理分页查询，category / entity 可选过滤。
     */
    List<AgentLongTermMemory> selectPage(
            @Param("userId") String userId,
            @Param("category") String category,
            @Param("entity") String entity,
            @Param("offset") int offset,
            @Param("size") int size
    );

    /**
     * 分页总数，过滤条件与 selectPage 保持一致。
     */
    long countByUser(
            @Param("userId") String userId,
            @Param("category") String category,
            @Param("entity") String entity
    );

    /**
     * always-on 召回：用户画像/偏好/约束，按重要度取前 N 条。
     */
    List<AgentLongTermMemory> selectAlwaysOn(@Param("userId") String userId, @Param("limit") int limit);

    /**
     * 实体等值 + 关键词重叠召回：entity 命中 或 keywords 与传入关键词重叠。
     * entities 与 keywords 均为空时返回空列表。
     */
    List<AgentLongTermMemory> selectByEntities(
            @Param("userId") String userId,
            @Param("entities") List<String> entities,
            @Param("keywords") List<String> keywords,
            @Param("limit") int limit
    );

    /**
     * 向量召回（EXPERIENCE 语义相似），按余弦相似度取前 N 条，并填充 similarity。
     */
    List<AgentLongTermMemory> selectTopKByEmbedding(
            @Param("userId") String userId,
            @Param("queryEmbedding") float[] queryEmbedding,
            @Param("topK") int topK
    );

    /**
     * 语义检索（管理端 search）：全分类余弦相似 Top-N，按相似度排序并填充 similarity。只读。
     */
    List<AgentLongTermMemory> selectSearchByEmbedding(
            @Param("userId") String userId,
            @Param("queryEmbedding") float[] queryEmbedding,
            @Param("topK") int topK
    );

    /**
     * 访问计数 +1 并刷新最近访问时间（被选中注入时调用）。
     */
    int updateAccessCount(@Param("userId") String userId, @Param("id") Long id);

    /**
     * 冲突消解后全量更新正文类字段（含重算后的 embedding）。
     */
    int updateContent(AgentLongTermMemory memory);

    /**
     * 更新重要度。
     */
    int updateImportance(
            @Param("userId") String userId,
            @Param("id") Long id,
            @Param("importance") Integer importance
    );

    /**
     * 统计某用户某分类的记忆条数（含未删除的过期行，供容量淘汰判断）。
     */
    long countByCategory(@Param("userId") String userId, @Param("category") String category);

    /**
     * 选取淘汰候选：低重要度 + 久未访问 + 低频优先。
     */
    List<AgentLongTermMemory> selectEvictCandidates(
            @Param("userId") String userId,
            @Param("category") String category,
            @Param("limit") int limit
    );

    /**
     * 逻辑删除单条。
     */
    int logicalDeleteById(@Param("userId") String userId, @Param("id") Long id);

    /**
     * 逻辑删除某用户某分类（category 为空表示全部）。
     */
    int logicalDeleteByCategory(@Param("userId") String userId, @Param("category") String category);

    /**
     * 逻辑删除某用户全部长期记忆。
     */
    int logicalDeleteByUser(@Param("userId") String userId);

    /**
     * 物理删除指定 id 集合（淘汰用）。
     */
    int physicalDeleteByIds(@Param("ids") List<Long> ids);

    /**
     * 定时清扫：物理删除逻辑删除、或已过期的行。
     */
    int deleteExpired();

    /**
     * 列出存在未删除记忆的用户-分类对，供定时任务做全量容量复查。
     */
    List<UserCategory> selectUserCategories();

    /**
     * 用户-分类对聚合投影（定时容量复查用）。
     */
    class UserCategory {

        private String userId;

        private String category;

        public String getUserId() {
            return userId;
        }

        public void setUserId(String userId) {
            this.userId = userId;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }
    }
}
