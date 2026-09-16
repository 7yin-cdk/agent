package com.library.agent.rag.service;

import com.library.agent.rag.dto.ParsedDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Locale;

/**
 * 文档解析入口，按文件类型分流。
 * <p>
 * markdown 走 {@link MarkdownDocumentParser}：保留空行与缩进，且不经 Tika
 * （避免 Tika 对 text/markdown 做二次转换带来的不确定性），为后续标题感知切分提供结构。
 * 其余格式（PDF 等）保持原有 Tika + 压平清洗路径不变。
 * <p>
 * 是否 markdown <b>只按文件名后缀判断</b>：浏览器上传 .md 时常把 Content-Type 报成
 * application/octet-stream，用 MIME 判断不可靠。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentParser {

    /** 正文最短长度，低于此值视为扫描版 PDF 或不可解析格式 */
    private static final int MIN_CONTENT_LENGTH = 50;

    /** markdown 扩展名 */
    private static final String MARKDOWN_SUFFIX = ".md";

    private final Tika tika = new Tika();

    private final MarkdownDocumentParser markdownDocumentParser;

    /**
     * 解析文档为可切分的正文。
     *
     * @param inputStream 文档流
     * @param fileName    原始文件名，用于判定是否 markdown，可为 null
     * @return 解析结果；非 markdown 文件只填 text
     */
    public ParsedDocument parse(InputStream inputStream, String fileName) {
        try {
            ParsedDocument document = isMarkdown(fileName)
                    ? markdownDocumentParser.parse(inputStream)
                    : parseWithTika(inputStream);

            if (document.getText() == null || document.getText().trim().length() < MIN_CONTENT_LENGTH) {
                throw new RuntimeException("文件解析失败：该文件可能为扫描版PDF或不支持的格式");
            }
            if (document.getSourceTitle() == null) {
                /* frontmatter 缺 title 时退回文件名，保证面包屑根节点与元数据列取值一致 */
                document.setSourceTitle(stripExtension(fileName));
            }
            return document;

        } catch (Exception e) {
            log.error("文档解析失败", e);
            throw new RuntimeException("文档解析失败：" + e.getMessage());
        }
    }

    /**
     * 非 markdown 文档沿用 Tika 解析 + 压平清洗。
     */
    private ParsedDocument parseWithTika(InputStream inputStream) throws Exception {
        Metadata metadata = new Metadata();
        String content = tika.parseToString(inputStream, metadata);
        log.info("Tika解析完成，长度: {}", content == null ? 0 : content.length());

        ParsedDocument document = new ParsedDocument();
        document.setText(cleanText(content));
        document.setMarkdown(false);
        return document;
    }

    /**
     * 是否 markdown 文档。
     */
    private boolean isMarkdown(String fileName) {
        return fileName != null && fileName.toLowerCase(Locale.ROOT).endsWith(MARKDOWN_SUFFIX);
    }

    /**
     * 去掉扩展名，作为缺省标题。
     */
    private String stripExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * 文本清洗（RAG关键步骤）。
     * <p>
     * 注意：这里会压平空行与缩进，只适用于 PDF 等无结构文本；
     * markdown 必须走 {@link MarkdownDocumentParser}，否则标题与代码块结构无法恢复。
     */
    private String cleanText(String text) {
        if (text == null) return "";

        return text
                .replaceAll("\\r", "")
                .replaceAll("\\n{2,}", "\n")
                .replaceAll("[ \\t]{2,}", " ")
                .trim();
    }
}
