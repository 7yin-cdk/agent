package com.library.agent.rag.dto;

import lombok.Data;

/**
 * 文档解析结果。
 * <p>
 * markdown 为 true 时，text 是已归一化的 markdown 正文（保留空行与缩进，
 * 这是后续按标题切分的前提），sourceTitle / sourceUrl 取自 YAML frontmatter。
 * 非 markdown 文件（PDF 等）两者为 null，text 走原有的压平清洗逻辑。
 */
@Data
public class ParsedDocument {

    /** 归一化后的正文文本 */
    private String text;

    /** 来源标题，取自 markdown frontmatter 的 title */
    private String sourceTitle;

    /** 来源地址，取自 markdown frontmatter 的 source_url */
    private String sourceUrl;

    /** 是否为 markdown 文档，决定后续走标题感知切分还是通用切分 */
    private boolean markdown;
}
