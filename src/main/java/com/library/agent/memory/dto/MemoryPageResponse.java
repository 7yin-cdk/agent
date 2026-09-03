package com.library.agent.memory.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 长期记忆分页响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemoryPageResponse {

    /**
     * 满足过滤条件的记忆总数
     */
    private long total;

    /**
     * 当前页码，从 1 开始
     */
    private int page;

    /**
     * 每页条数
     */
    private int size;

    /**
     * 当前页数据
     */
    private List<MemoryView> items = new ArrayList<>();
}
