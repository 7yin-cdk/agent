package com.library.agent.rag.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）倒排融合工具。
 * <p>
 * 将多路召回的 id 列表按其排名加权融合：score = Σ 1/(60 + rank)，取 topK。
 * 生产 RAG 与检索调试共用，保证两处召回行为一致。
 */
public final class RrfMerger {

    private static final double K = 60.0;

    private RrfMerger() {
    }

    public static List<Long> merge(List<Long> vectorIds, List<Long> keywordIds, int limit) {
        Map<Long, Double> scores = new LinkedHashMap<>();
        addScores(scores, vectorIds);
        addScores(scores, keywordIds);

        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static void addScores(Map<Long, Double> scores, List<Long> ids) {
        for (int i = 0; i < ids.size(); i++) {
            scores.merge(ids.get(i), 1.0 / (K + i + 1), Double::sum);
        }
    }
}
