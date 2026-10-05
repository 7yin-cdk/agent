package com.library.agent.rag.dto;

import lombok.Data;

import java.util.List;

/**
 * 检索各阶段的有序 chunkId 快照，供测评 harness 计算召回与排名指标。
 * <p>
 * 只记录顺序、不记录分数：Recall@k / MRR / nDCG@k 等指标只需要「某阶段输出里前 k 个是哪些 chunk」，
 * 不需要分数量值，因此检索算法本身不用改，只是在既有步骤之间把中间结果抄一份出来。
 * <p>
 * 生产链路不传该对象（传 null），此时检索行为与改造前完全一致。
 */
@Data
public class RetrievalStageTrace {

    /** 向量路 top100，pgvector 距离升序 */
    private List<Long> vectorIds;

    /** 关键词路 top100，ES 相关度降序 */
    private List<Long> keywordIds;

    /** RRF 融合 top80，重排前的顺序 */
    private List<Long> mergedIds;

    /** fileId 过滤后的顺序，是 mergedIds 的子序列 */
    private List<Long> candidateIds;

    /** rerank 后的顺序；未启用 rerank 时为 null，与「重排后为空」区分 */
    private List<Long> rerankedIds;

    /** 截断 limit 后最终输出的顺序 */
    private List<Long> finalIds;

    /** 本次生效的截断长度 */
    private Integer limit;

    /** 本次是否实际执行了 rerank */
    private Boolean rerankApplied;
}
