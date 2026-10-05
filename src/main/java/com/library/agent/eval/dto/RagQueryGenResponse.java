package com.library.agent.eval.dto;

import lombok.Data;

import java.util.List;

/**
 * 反向生成测试 query 的响应。
 */
@Data
public class RagQueryGenResponse {

    /**
     * 生成的候选提问，按类型各一条
     */
    private List<Item> queries;

    /**
     * 实际使用的模型名，写入测试集便于追溯
     */
    private String model;

    /**
     * 单条候选提问。
     */
    @Data
    public static class Item {

        /**
         * 提问正文
         */
        private String query;

        /**
         * 提问类型：concept / param / troubleshoot / howto / compare
         */
        private String type;
    }
}
