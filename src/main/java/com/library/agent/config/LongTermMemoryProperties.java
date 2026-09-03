package com.library.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 长期记忆（Long-Term Memory）配置。
 * <p>
 * 对应 application.yaml 中 agent.ltm 配置块，覆盖召回各阶段条数/阈值、
 * 各分类容量上限、淘汰定时任务 cron、抽取触发门槛。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.ltm")
public class LongTermMemoryProperties {

    /**
     * always-on（画像/偏好/约束）注入条数上限，默认 5。
     */
    private int alwaysOnLimit = 5;

    /**
     * 实体等值召回条数，默认 8。
     */
    private int entityRecallLimit = 8;

    /**
     * 向量召回候选数（EXPERIENCE），默认 20。
     */
    private int vectorTopK = 20;

    /**
     * RRF 融合后候选数，默认 10。
     */
    private int rrfLimit = 10;

    /**
     * rerank 最终保留条数，默认 3。
     */
    private int rerankTopN = 3;

    /**
     * rerank 最低分数，低于该值的候选丢弃，默认 0.3。
     */
    private double rerankMinScore = 0.3;

    /**
     * 最终注入 prompt 的长期记忆总条数上限，默认 6。
     */
    private int injectLimit = 6;

    /**
     * 各分类每用户容量上限（永久类软上限、淘汰类硬上限）。
     */
    private Capacity capacity = new Capacity();

    /**
     * 淘汰定时任务配置。
     */
    private Eviction eviction = new Eviction();

    /**
     * 抽取触发配置。
     */
    private Extraction extraction = new Extraction();

    /**
     * 各记忆分类的容量上限。
     */
    @Data
    public static class Capacity {

        /**
         * 用户画像上限，默认 50。
         */
        private int userProfile = 50;

        /**
         * 偏好上限，默认 50。
         */
        private int preference = 50;

        /**
         * 约束上限，默认 50。
         */
        private int constraint = 50;

        /**
         * 实体知识上限，默认 200。
         */
        private int entity = 200;

        /**
         * 经验案例上限，默认 200。
         */
        private int experience = 200;

        /**
         * 按记忆类别（MemoryCategory 常量，如 USER_PROFILE）取容量上限。
         *
         * @param category 记忆类别常量，null/未知返回 0
         * @return 该类别每用户容量上限
         */
        public int capacityFor(String category) {
            if (category == null) {
                return 0;
            }
            switch (category) {
                case "USER_PROFILE":
                    return userProfile;
                case "PREFERENCE":
                    return preference;
                case "CONSTRAINT":
                    return constraint;
                case "ENTITY":
                    return entity;
                case "EXPERIENCE":
                    return experience;
                default:
                    return 0;
            }
        }
    }

    /**
     * 淘汰定时任务配置。
     */
    @Data
    public static class Eviction {

        /**
         * 每日清扫 cron（六段），默认每天 03:17:00。
         */
        private String cron = "0 17 3 * * *";
    }

    /**
     * 抽取触发配置。
     */
    @Data
    public static class Extraction {

        /**
         * 触发抽取的最少轮内消息数，默认 2。
         */
        private int minTurnMessages = 2;
    }
}
