package com.library.agent.rag.service;

import com.library.agent.rag.dto.ParsedDocument;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * markdown 文档解析器：剥离 YAML frontmatter，并归一化转换过程引入的噪声。
 * <p>
 * 与 {@link DocumentParser} 的 cleanText 根本差别是本类<b>保留空行与缩进</b>——
 * 标题层级、代码围栏、表格都依赖这些结构识别，一旦压平（如把连续空行折叠为单换行）
 * 就无法恢复，后续不可能再做标题感知切分。因此这里只清理噪声，不做结构性改写。
 * <p>
 * 注意：缩进代码围栏（形如 "    ```"）不在此处顶格改写，而是由
 * {@link MarkdownChunker} 在识别围栏时容忍前导空白，避免改动正文内容。
 */
@Component
public class MarkdownDocumentParser {

    /** frontmatter 分隔符 */
    private static final String FRONTMATTER_DELIMITER = "---";

    /* 以下三个不可见字符常量以码点定义，避免源码中出现不可见字面量 */
    /** 字节顺序标记，出现在文件头会干扰 frontmatter 起始判断 */
    private static final char BOM_CHAR = 0xFEFF;

    /** 不换行空格：PG 文档交叉引用 "Section 24.1" 用的就是它，会破坏子串匹配 */
    private static final char NBSP_CHAR = 0x00A0;

    /** 零宽空格：转换残留，不可见但会污染 embedding */
    private static final char ZWSP_CHAR = 0x200B;

    /** frontmatter 的 "key: value" 行 */
    private static final Pattern FRONTMATTER_ENTRY =
            Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*):\\s*(.*)$");

    /** 标题行：最多 3 个前导空格 + 1~6 个 # + 空白 */
    private static final Pattern HEADING_LINE =
            Pattern.compile("^[ \\t]{0,3}#{1,6}[ \\t]+\\S.*$");

    /** 标题尾部残留的锚点符号，形如 "## VACUUM #" */
    private static final Pattern HEADING_ANCHOR_SUFFIX = Pattern.compile("\\s+#\\s*$");

    /**
     * 解析 markdown 输入流。
     *
     * @param inputStream markdown 原文流，按 UTF-8 解码
     * @return 正文已归一化的解析结果，frontmatter 缺失时 sourceTitle / sourceUrl 为 null
     */
    public ParsedDocument parse(InputStream inputStream) throws IOException {
        String content = decode(inputStream);
        Frontmatter frontmatter = splitFrontmatter(content);

        ParsedDocument document = new ParsedDocument();
        document.setText(normalize(frontmatter.body));
        document.setSourceTitle(frontmatter.title);
        document.setSourceUrl(frontmatter.url);
        document.setMarkdown(true);
        return document;
    }

    /**
     * 读取全部字节并按 UTF-8 解码，统一换行符、去掉 BOM。
     */
    private String decode(InputStream inputStream) throws IOException {
        String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        if (!content.isEmpty() && content.charAt(0) == BOM_CHAR) {
            content = content.substring(1);
        }
        return content.replace("\r\n", "\n").replace("\r", "\n");
    }

    /**
     * 剥离首个 frontmatter 块。
     * <p>
     * 只读取 title 与 source_url 两个键，不引入 YAML 依赖——本批语料的写法固定为
     * {@code key: "value"}，逐行匹配即可，无需完整 YAML 解析能力。
     */
    private Frontmatter splitFrontmatter(String content) {
        if (!content.startsWith(FRONTMATTER_DELIMITER + "\n")) {
            return new Frontmatter(null, null, content);
        }
        int closing = content.indexOf("\n" + FRONTMATTER_DELIMITER, FRONTMATTER_DELIMITER.length());
        if (closing < 0) {
            return new Frontmatter(null, null, content);
        }
        String block = content.substring(FRONTMATTER_DELIMITER.length() + 1, closing);
        int bodyStart = content.indexOf('\n', closing + 1);
        String body = bodyStart < 0 ? "" : content.substring(bodyStart + 1);
        return new Frontmatter(readValue(block, "title"), readValue(block, "source_url"), body);
    }

    /**
     * 从 frontmatter 块中取指定键的值，未命中返回 null。
     */
    private String readValue(String block, String key) {
        for (String line : block.split("\n")) {
            Matcher matcher = FRONTMATTER_ENTRY.matcher(line);
            if (matcher.matches() && key.equals(matcher.group(1))) {
                return unquote(matcher.group(2).trim());
            }
        }
        return null;
    }

    /**
     * 去掉值两侧的成对引号。
     */
    private String unquote(String value) {
        boolean doubleQuoted = value.startsWith("\"") && value.endsWith("\"");
        boolean singleQuoted = value.startsWith("'") && value.endsWith("'");
        if (value.length() >= 2 && (doubleQuoted || singleQuoted)) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 逐行归一化正文。
     * <p>
     * 围栏内的内容是代码，必须原样保留：既不替换转义符，也不参与空行折叠，
     * 否则代码块里的空行会被吃掉。围栏本身用「容忍前导空白」的方式识别，
     * 因此缩进围栏无需顶格也能被正确配对。
     */
    private String normalize(String body) {
        StringBuilder result = new StringBuilder(body.length());
        boolean inFence = false;
        boolean pendingBlank = false;

        for (String rawLine : body.split("\n", -1)) {
            String line = sanitize(rawLine);
            boolean fence = MarkdownChunker.isFenceLine(line);

            if (!inFence && line.isBlank()) {
                pendingBlank = result.length() > 0;
                continue;
            }
            if (pendingBlank) {
                result.append('\n');
                pendingBlank = false;
            }
            result.append(inFence ? line : cleanProse(line, fence)).append('\n');

            if (fence) {
                inFence = !inFence;
            }
        }
        return result.toString().strip();
    }

    /**
     * 围栏外的行清理：标题去锚点残留、还原转义的下划线。
     */
    private String cleanProse(String line, boolean fence) {
        String cleaned = line;
        if (!fence && HEADING_LINE.matcher(cleaned).matches()) {
            cleaned = HEADING_ANCHOR_SUFFIX.matcher(cleaned).replaceAll("");
        }
        return cleaned.replace("\\_", "_");
    }

    /**
     * 单行噪声清理：不换行空格、零宽空格、行尾空白。
     */
    private String sanitize(String line) {
        return line
                .replace(NBSP_CHAR, ' ')
                .replace(String.valueOf(ZWSP_CHAR), "")
                .replaceAll("[ \\t]+$", "");
    }

    /**
     * frontmatter 拆分结果：来源标题、来源地址、剩余正文。
     */
    private static final class Frontmatter {

        private final String title;
        private final String url;
        private final String body;

        private Frontmatter(String title, String url, String body) {
            this.title = title;
            this.url = url;
            this.body = body;
        }
    }
}
