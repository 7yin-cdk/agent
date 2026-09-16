package com.library.agent.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 分块草稿：分块器输出、入库前的中间结构。
 * <p>
 * text 已包含小节面包屑前缀，可直接向量化与入库；
 * startOffset / endOffset 是该块内容在归一化正文中的字符位置（不含面包屑前缀与重叠区）。
 */
@Data
@AllArgsConstructor
public class ChunkDraft {

    /** 分块文本，形如 "&lt;小节路径&gt;\n\n&lt;正文&gt;" */
    private String text;

    /** 小节路径，如 "VACUUM &gt; Synopsis" */
    private String sectionPath;

    /** 在归一化正文中的起始字符偏移 */
    private int startOffset;

    /** 在归一化正文中的结束字符偏移 */
    private int endOffset;
}
