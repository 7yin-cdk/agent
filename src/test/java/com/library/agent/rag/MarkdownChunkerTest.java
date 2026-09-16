package com.library.agent.rag;

import com.library.agent.rag.dto.ChunkDraft;
import com.library.agent.rag.dto.ParsedDocument;
import com.library.agent.rag.service.MarkdownChunker;
import com.library.agent.rag.service.MarkdownDocumentParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * markdown 分块器验收测试，直接跑真实语料，不起 Spring 上下文。
 * <p>
 * 覆盖四项在本批 PostgreSQL 文档上实测会出问题的不变量：
 * 代码围栏不被截断、内容不丢失、每个块带小节面包屑、切分粒度粗于暴力切分。
 */
class MarkdownChunkerTest {

    /** 真实语料目录，相对项目根 */
    private static final Path CORPUS = Path.of("docs", "RAG", "markdown");

    /** 旧链路的切分粒度，用于对比块数 */
    private static final int BRUTE_FORCE_SIZE = 400;

    /** 超过此长度的行可能被强制截断，不参与「内容不丢失」断言 */
    private static final int LONG_LINE = 200;

    /** 旧实现对超长块中长度超过此值的表格行做截断，实测丢了 7 行共 4106 字符 */
    private static final int TRUNCATION_THRESHOLD = 800;

    /** 标题行，与分块器保持同一判定 */
    private static final Pattern HEADING = Pattern.compile("^[ \\t]{0,3}#{1,6}[ \\t]+.+$");

    /** 重叠区长度上限，取自 MarkdownChunker 的 OVERLAP_SIZE，留些余量 */
    private static final int OVERLAP_LIMIT = 128;

    private final MarkdownDocumentParser parser = new MarkdownDocumentParser();
    private final MarkdownChunker chunker = new MarkdownChunker();

    /**
     * 每个块都必须以自身小节路径开头，否则片段脱离原文后无法自证归属。
     */
    @Test
    void everyChunkCarriesSectionBreadcrumb() throws IOException {
        Sample sample = load("sql-vacuum.md");
        List<ChunkDraft> chunks = chunker.chunk(sample.body, sample.title);

        assertFalse(chunks.isEmpty(), "sql-vacuum.md 未产出任何分块");
        for (ChunkDraft chunk : chunks) {
            assertNotNull(chunk.getSectionPath(), "存在小节路径为 null 的块");
            assertFalse(chunk.getSectionPath().isBlank(), "存在小节路径为空的块");
            assertTrue(chunk.getText().startsWith(chunk.getSectionPath() + "\n\n"),
                    "块未以小节路径开头，实际首行：" + firstLine(chunk.getText()));
        }
    }

    /**
     * 无 H1 的文档（本批 133/141 个）应把正文首个标题作为合成根，
     * 使 "## Synopsis" 得到 "VACUUM > Synopsis"，而不是丢失文档身份的 "Synopsis"。
     */
    @Test
    void syntheticRootKeepsDocumentIdentity() throws IOException {
        Sample sample = load("sql-vacuum.md");
        List<ChunkDraft> chunks = chunker.chunk(sample.body, sample.title);

        assertTrue(chunks.stream().anyMatch(c -> "VACUUM > Synopsis".equals(c.getSectionPath())),
                "未生成合成根，实际小节路径样本：" + chunks.stream()
                        .map(ChunkDraft::getSectionPath).distinct().limit(5).toList());
    }

    /**
     * 跨小节合并时的面包屑：嵌套合并取最深的小节，并列合并退到公共父级。
     * <p>
     * 正文不足 {@code MIN_CHUNK_SIZE} 的小节（本批 42 个）会被并入相邻的块，两种形态归属不同：
     * 并入的是自己的子小节时，内容仍在深侧小节的子树里，标最深的小节即可——退到根会让同一文档
     * 的许多块拿到同一个名字；两个小节是兄弟时（如 {@code … > Notes} 与 {@code … > Examples}）
     * 没有哪个盖得住两边，标任何一边都是张冠李戴。
     */
    @Test
    void mergedChunksTakeDeepestCoveringSectionOrCommonAncestor() {
        String nested = "## Topic\n\n" + sentences(1) + "\n\n### Child\n\n" + sentences(6) + "\n";
        List<ChunkDraft> nestedChunks = chunker.chunk(nested, "Topic");
        assertEquals(1, nestedChunks.size(), "短导语应与子小节的正文合并成一个块");
        assertEquals("Topic > Child", nestedChunks.get(0).getSectionPath(),
                "嵌套合并应标最深的小节，而不是退到根");

        String sibling = "## Topic\n\n### First\n\n" + sentences(1) + "\n\n### Second\n\n" + sentences(1) + "\n";
        List<ChunkDraft> siblingChunks = chunker.chunk(sibling, "Topic");
        assertEquals(1, siblingChunks.size(), "两个短小节应合并成一个块");
        assertEquals("Topic", siblingChunks.get(0).getSectionPath(),
                "并列合并应标公共父级，而不是偏袒其中任一个小节");
    }

    /**
     * 代码围栏是原子块：任何块内出现的围栏行必须成对，否则代码块被切碎。
     */
    @Test
    void fencesAreNeverSplitAcrossChunks() throws IOException {
        for (String name : List.of("sql-vacuum.md", "monitoring-stats.md", "wal-configuration.md",
                "indexes-intro.md", "warm-standby.md", "xfunc-c.md")) {
            Sample sample = load(name);
            for (ChunkDraft chunk : chunker.chunk(sample.body, sample.title)) {
                assertEquals(0, fenceLineCount(chunk.getText()) % 2,
                        name + " 存在围栏失配的块，首行：" + firstLine(chunk.getText()));
            }
        }
    }

    /**
     * 除标题（由面包屑承载）与水平线（纯分隔）外，正文行不得丢失。
     * <p>
     * 逐字符比较会在正文按句切分处误报：一个物理行被分到两个块的边界上后，
     * 拼接结果里原本的空格变成了换行。因此比较时忽略空白，只校验字符序列本身没少。
     */
    @Test
    void noContentLineIsLost() throws IOException {
        for (String name : List.of("sql-vacuum.md", "monitoring-stats.md", "wal.md", "glossary.md")) {
            Sample sample = load(name);
            String combined = compact(String.join("\n", bodies(chunker.chunk(sample.body, sample.title))));

            for (String line : expectedContentLines(sample.body)) {
                assertTrue(combined.contains(compact(line)), name + " 丢失内容行：" + line);
            }
        }
    }

    /**
     * 标题感知切分应产出比按 400 字符暴力切分更少的块——这正是「保住表格与代码块」的代价体现，
     * 块数若不降反升，说明结构保护没有生效。
     */
    @Test
    void chunkingIsCoarserThanBruteForceSplitting() throws IOException {
        Sample sample = load("monitoring-stats.md");
        List<ChunkDraft> chunks = chunker.chunk(sample.body, sample.title);
        int bruteForce = (sample.body.length() + BRUTE_FORCE_SIZE - 1) / BRUTE_FORCE_SIZE;

        assertTrue(chunks.size() < bruteForce,
                "块数应少于按 400 字符暴力切分：" + chunks.size() + " vs " + bruteForce);
    }

    /**
     * 纯目录页（只有 TOC 与一句导语）不应抛异常，且仍能产出带面包屑的块。
     */
    @Test
    void tocOnlyDocumentIsHandled() throws IOException {
        Sample sample = load("wal.md");
        List<ChunkDraft> chunks = chunker.chunk(sample.body, sample.title);

        assertFalse(chunks.isEmpty(), "wal.md 未产出任何分块");
        chunks.forEach(chunk -> assertTrue(chunk.getText().startsWith(chunk.getSectionPath() + "\n\n")));
    }

    /**
     * 目录块是纯小节名堆砌，不含答案却极易命中关键词与向量，必须整块丢弃。
     * <p>
     * 取两个正文前带目录的内容页与一个章封面页，断言没有块残留 3 行以上目录条目。
     */
    @Test
    void tocBlocksAreNotIngested() throws IOException {
        for (String name : List.of("runtime-config-logging.md", "monitoring-stats.md", "high-availability.md")) {
            Sample sample = load(name);
            List<ChunkDraft> chunks = chunker.chunk(sample.body, sample.title);

            assertFalse(chunks.isEmpty(), name + " 未产出任何分块");
            for (ChunkDraft chunk : chunks) {
                long entries = Arrays.stream(chunk.getText().split("\n", -1))
                        .filter(MarkdownChunker::isTocEntry)
                        .count();
                assertTrue(entries < 3, name + " 的块残留 " + entries + " 行目录条目，首行："
                        + firstLine(chunk.getText()));
            }
        }
    }

    /**
     * 章封面页的目录后面那段导语有定位价值，跳过目录后必须保留。
     */
    @Test
    void landingPageIntroSurvivesTocSkip() throws IOException {
        Sample sample = load("high-availability.md");
        String combined = chunker.chunk(sample.body, sample.title).stream()
                .map(ChunkDraft::getText)
                .collect(Collectors.joining("\n"));

        assertTrue(combined.contains("trade-off between functionality and performance"),
                "章封面页的导语被一并丢弃了");
    }

    /**
     * 超长表格里的长单元格必须整行保留。
     * <p>
     * 本批 functions-admin.md 与 monitoring-stats.md 的表格中存在单行上千字符的单元格（最长 2211），
     * 旧实现对超过 800 的行做 substring 截断：实测丢 7 行共 4106 字符，且断在词或句子中间。
     */
    @Test
    void splitTableKeepsLongRowsIntact() throws IOException {
        for (String name : List.of("functions-admin.md", "monitoring-stats.md")) {
            Sample sample = load(name);
            String combined = chunker.chunk(sample.body, sample.title).stream()
                    .map(ChunkDraft::getText)
                    .collect(Collectors.joining("\n"));

            List<String> longRows = tableRowsLongerThan(sample.body, TRUNCATION_THRESHOLD);
            assertFalse(longRows.isEmpty(), name + " 语料里没有超长表格行，这条断言失去意义");
            for (String row : longRows) {
                assertTrue(combined.contains(row), name + " 的表格长行被截断，行首："
                        + row.substring(0, Math.min(60, row.length())));
            }
        }
    }

    /**
     * 超长表格切分后，每个片段都要带表头（列名行 + 分隔行），否则数据行读不出各列的含义，
     * 也不再是合法的 markdown 表格。
     * <p>
     * 用合成表格而非真实语料：真实文档的表格后面常接正文，正文块会因 100 字符重叠继承表格尾部若干行，
     * 那些块本来就该没有表头，会把断言带偏。
     */
    @Test
    void splitTableFragmentsRepeatHeader() {
        String header = "| name | type | default | note |";
        String separator = "| --- | --- | --- | --- |";
        StringBuilder table = new StringBuilder("## Big Table\n\n")
                .append(header).append('\n').append(separator).append('\n');
        for (int i = 0; i < 60; i++) {
            table.append("| parameter_").append(i).append(" | integer | ").append(i)
                    .append(" | description text for parameter ").append(i).append(" |\n");
        }

        List<ChunkDraft> chunks = chunker.chunk(table.toString(), "Big Table");
        assertTrue(chunks.size() > 1, "合成表格未被切开，无法验证片段补表头：" + chunks.size());
        for (ChunkDraft chunk : chunks) {
            assertTrue(chunk.getText().contains(header),
                    "片段缺表头行，实际首行：" + firstLine(chunk.getText()));
            assertTrue(chunk.getText().contains(separator),
                    "片段缺分隔行，实际首行：" + firstLine(chunk.getText()));
        }
    }

    /**
     * 超过 800 字符的正文按句号切成多段，每段都以句末标点收尾，不再从句子中间硬切。
     */
    @Test
    void longParagraphSplitsAtSentenceBoundaries() {
        List<ChunkDraft> chunks = chunker.chunk("## Notes\n\n" + sentences(20), "Notes");

        assertTrue(chunks.size() > 1, "长段落未被切开：" + chunks.size());
        for (String body : bodies(chunks)) {
            assertTrue(body.stripTrailing().endsWith("."), "未断在句末：" + firstLine(body));
        }
    }

    /**
     * 同一个原子块被切开后，后续片段要带上一片段末尾的重叠区，跨段的概念才不会被割裂。
     */
    @Test
    void splitParagraphSegmentsShareOverlap() {
        List<ChunkDraft> chunks = chunker.chunk("## Notes\n\n" + sentences(20), "Notes");
        assertEquals(2, chunks.size(), "预期切出两段，实际：" + chunks.size());

        String previous = body(chunks.get(0));
        String overlap = firstLine(body(chunks.get(1)));
        assertTrue(previous.stripTrailing().endsWith(overlap), "第二段未带重叠区，其首行：" + overlap);
    }

    /**
     * 相邻的两个原子块之间不加重叠区：原子块的第一段必须干净，
     * 否则上一块的收尾内容会被注进下一块的开头。
     */
    @Test
    void adjacentBlocksShareNoOverlap() {
        String alpha = "Alpha. " + sentences(9);
        String beta = "Beta. " + sentences(9);
        List<ChunkDraft> chunks = chunker.chunk("## Notes\n\n" + alpha + "\n\n" + beta, "Notes");

        assertTrue(chunks.size() > 1, "两个段落未被分开：" + chunks.size());
        for (int i = 1; i < chunks.size(); i++) {
            String previous = body(chunks.get(i - 1));
            String head = firstLine(body(chunks.get(i)));
            assertFalse(previous.stripTrailing().endsWith(head),
                    "第 " + i + " 块多出了上一块的结尾：" + head);
        }
    }

    /**
     * 小数点与千分位逗号不是切点，否则 "18.0"、"1,024" 这类数字会被拆到两个块里。
     */
    @Test
    void punctuationSplitDoesNotBreakNumbers() {
        String text = "See version 18.0 of the server, and 1,024 rows were read. ".repeat(16);
        List<ChunkDraft> chunks = chunker.chunk("## Notes\n\n" + text, "Notes");

        assertTrue(chunks.size() > 1, "样本未超过 800 字符：" + text.length());
        String combined = chunks.stream().map(ChunkDraft::getText).collect(Collectors.joining("\n"));
        assertTrue(combined.contains("version 18.0"), "版本号被切断");
        assertTrue(combined.contains("1,024 rows"), "千分位数字被切断");
    }

    /**
     * 正文块不得超过目标大小：代码与表格有「结构完整大于块大小」的理由，正文没有。
     */
    @Test
    void textChunksStayWithinTarget() throws IOException {
        for (String name : List.of("sql-vacuum.md", "monitoring-stats.md", "runtime-config-logging.md")) {
            Sample sample = load(name);
            for (String body : bodies(chunker.chunk(sample.body, sample.title))) {
                boolean structured = containsTable(body) || body.lines().anyMatch(MarkdownChunker::isFenceLine);
                assertTrue(structured || body.length() <= 800,
                        name + " 的正文块超过 800：" + body.length() + "，" + firstLine(body));
            }
        }
    }

    /* ==================== 辅助 ==================== */

    /**
     * 读取语料原文并走真实的解析链路（frontmatter 剥离 + 归一化）。
     */
    private Sample load(String fileName) throws IOException {
        Path path = CORPUS.resolve(fileName);
        assertTrue(Files.exists(path), "语料文件不存在：" + path.toAbsolutePath());
        try (InputStream in = Files.newInputStream(path)) {
            ParsedDocument document = parser.parse(in);
            return new Sample(document.getText(), document.getSourceTitle());
        }
    }

    /**
     * 生成 count 句各约 67 字符的正文，用于构造超过 800 字符的长段落。
     */
    private String sentences(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append("Sentence number ").append(i)
                    .append(" carries a fair amount of detail about the topic. ");
        }
        return text.toString();
    }

    /**
     * 取各块的正文（剥掉面包屑与重叠区）：重叠区是块外的注入内容，
     * 校验「内容不丢失」与「块大小」时必须先剔除，否则会把重复的文字当成正文。
     */
    private List<String> bodies(List<ChunkDraft> chunks) {
        List<String> bodies = new ArrayList<>();
        String previous = null;
        for (ChunkDraft chunk : chunks) {
            String body = body(chunk);
            bodies.add(withoutOverlap(body, previous));
            previous = body;
        }
        return bodies;
    }

    /**
     * 取单块的正文。
     */
    private String body(ChunkDraft chunk) {
        return chunk.getText().substring(chunk.getSectionPath().length() + 2);
    }

    /**
     * 剔除重叠区：注入时形如「重叠区 + 换行 + 本块正文」，且重叠区必是上一块正文的结尾，
     * 因此在块的每个换行位置试一次后缀匹配即可，长度还须在上限之内。
     */
    private String withoutOverlap(String body, String previousBody) {
        if (previousBody == null) {
            return body;
        }
        String trimmed = previousBody.stripTrailing();
        for (int at = body.indexOf('\n'); at > 0 && at <= OVERLAP_LIMIT; at = body.indexOf('\n', at + 1)) {
            if (trimmed.endsWith(body.substring(0, at))) {
                return body.substring(at + 1);
            }
        }
        return body;
    }

    /**
     * 去掉全部空白，用于与切分结果做与换行无关的内容比对。
     */
    private String compact(String text) {
        return text.replaceAll("\\s+", "");
    }

    /**
     * 正文里是否含表格行。
     */
    private boolean containsTable(String body) {
        return body.lines().anyMatch(line -> line.strip().startsWith("|"));
    }

    /**
     * 统计围栏行数，用于校验成对性。
     */
    private long fenceLineCount(String text) {
        return Arrays.stream(text.split("\n", -1)).filter(MarkdownChunker::isFenceLine).count();
    }

    /**
     * 期望被完整保留的正文行：排除空行、水平线、标题（由面包屑承载）、目录条目与超长行。
     * <p>
     * 目录条目按行排除，比切分器的「连续 3 行以上才算目录」更宽松：只会少断言几行，不会误报失败。
     */
    private List<String> expectedContentLines(String body) {
        List<String> lines = new ArrayList<>();
        boolean inFence = false;
        for (String line : body.split("\n", -1)) {
            if (MarkdownChunker.isFenceLine(line)) {
                inFence = !inFence;
                continue;
            }
            String stripped = line.strip();
            if (stripped.isEmpty() || isThematicBreak(stripped) || MarkdownChunker.isTocLabel(stripped)) {
                continue;
            }
            if (!inFence && (HEADING.matcher(line).matches() || MarkdownChunker.isTocEntry(line))) {
                continue;
            }
            if (stripped.length() > LONG_LINE) {
                continue;
            }
            lines.add(stripped);
        }
        return lines;
    }

    /**
     * 水平线只作块分隔，不产出内容。
     */
    private boolean isThematicBreak(String stripped) {
        if (stripped.length() < 3) {
            return false;
        }
        char first = stripped.charAt(0);
        return (first == '-' || first == '*' || first == '_') && stripped.chars().allMatch(c -> c == first);
    }

    /**
     * 取出正文中长度超过 limit 的表格行，用于校验长行未被截断。
     */
    private List<String> tableRowsLongerThan(String body, int limit) {
        return Arrays.stream(body.split("\n", -1))
                .map(String::strip)
                .filter(line -> line.startsWith("|") && line.length() > limit)
                .toList();
    }

    /**
     * 取首行，用于失败信息定位。
     */
    private String firstLine(String text) {
        int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }

    /**
     * 语料样本：归一化正文与来源标题。
     */
    private record Sample(String body, String title) {
    }
}
