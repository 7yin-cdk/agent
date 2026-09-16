package com.library.agent.rag.service;

import com.library.agent.rag.dto.ChunkDraft;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * markdown 标题感知分块器。
 * <p>
 * 与按标点暴力切分（{@code RagAsyncProcessor.splitText}）的根本差别是这里理解 markdown 结构：
 * 代码围栏、表格、一组连续正文行各自是<b>原子块</b>，分块边界优先落在标题层级上；
 * 每个 chunk 的正文前会加上小节面包屑（如 "VACUUM &gt; Synopsis"），
 * 使片段脱离原文后仍能自证上下文归属——这一步同时作用于向量、关键词索引与 rerank 输入。
 * <p>
 * 三级尺度，避免一刀切：
 * <ul>
 *     <li>代码与表格超过 {@link #HARD_MAX_CHUNK_SIZE} 才按行细分，因为结构完整比块大小更重要；</li>
 *     <li>正文超过 {@link #MAX_CHUNK_SIZE} 即按 "." "," 递归细分，正文没有保留超长块的理由；</li>
 *     <li>打包不超过 {@link #MAX_CHUNK_SIZE}、小节变化不硬拼，低于 {@link #MIN_CHUNK_SIZE} 的碎片并入邻块。</li>
 * </ul>
 * 重叠区只在同一个原子块被切开的相邻片段之间添加，原子块的第一段不带重叠区——
 * 跨原子块的拼接会把代码或表格行注入正文，得不偿失。
 */
@Component
public class MarkdownChunker {

    /** 目标块大小（字符） */
    private static final int MAX_CHUNK_SIZE = 800;

    /** 小节变化时另起新块的最小缓冲长度，低于此值则并入下一块 */
    private static final int MIN_CHUNK_SIZE = 200;

    /** 单个原子块的硬上限，超过才按行细分，以免大表格/大代码块被无谓切碎 */
    private static final int HARD_MAX_CHUNK_SIZE = 2400;

    /** 相邻块之间的重叠字符数，缓解边界跨块的概念被割裂 */
    private static final int OVERLAP_SIZE = 100;

    /** 面包屑中层级之间的连接符 */
    private static final String SECTION_SEPARATOR = " > ";

    /** 面包屑与正文之间的空行 */
    private static final String BREADCRUMB_SEPARATOR = "\n\n";

    /** 代码围栏标记 */
    private static final String FENCE_MARKER = "```";

    /** 表格行起始标记 */
    private static final char TABLE_MARKER = '|';

    /** 表格的分隔行，形如 | --- | --- |，与首行共同构成表头 */
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\|[-\\s:|]+\\|$");

    /** 正文切分的分隔符，由粗到细：先按句号切，整句仍超长才退到逗号 */
    private static final List<String> TEXT_SEPARATORS = List.of(".", ",");

    /** 无标题文档时使用的兜底根节点 */
    private static final String DEFAULT_ROOT = "Document";

    /** 标题行：最多 3 个前导空格 + 1~6 个 # + 空白 + 标题文本 */
    private static final Pattern HEADING = Pattern.compile("^[ \\t]{0,3}(#{1,6})[ \\t]+(.+?)\\s*$");

    /** 目录条目：可选 ": " 前缀 + 点分编号 + 标题，如 "19.8.1. Where to Log" */
    private static final Pattern TOC_ENTRY = Pattern.compile("^\\s*(?::\\s+)?\\d+(\\.\\d+)+\\.?\\s+\\S.*$");

    /** 章封面页的目录标记行 */
    private static final Pattern TOC_LABEL = Pattern.compile("^\\*\\*Table of Contents\\*\\*$");

    /** 目录条目长度上限，超过视为正文；本批语料最长的目录条目为 68 字符 */
    private static final int TOC_ENTRY_MAX_LENGTH = 100;

    /** 连续条目数达标才判定为目录，低于此值视为正文里的编号列表 */
    private static final int MIN_TOC_ENTRIES = 3;

    /**
     * 判断一行是否为代码围栏。
     * <p>
     * 容忍前导空白，因为本批语料有 86 处围栏嵌在列表项里（形如 "    ```"）。
     * 这样无需顶格改写正文，围栏配对也不会漏。
     */
    public static boolean isFenceLine(String line) {
        return line.stripLeading().startsWith(FENCE_MARKER);
    }

    /**
     * 按 markdown 结构把正文切分为分块草稿。
     *
     * @param markdown      已归一化的 markdown 正文（空行与缩进必须保留）
     * @param fallbackTitle 正文无标题时使用的根节点名，通常取 frontmatter title 或文件名
     * @return 分块草稿，text 已含面包屑与重叠区；正文为空时返回空列表
     */
    public List<ChunkDraft> chunk(String markdown, String fallbackTitle) {
        if (markdown == null || markdown.isBlank()) {
            return List.of();
        }
        List<Block> blocks = expand(toBlocks(markdown, fallbackTitle));
        if (blocks.isEmpty()) {
            return List.of();
        }
        return assemble(pack(blocks));
    }

    /* ==================== 第一阶段：扫描成原子块 ==================== */

    /**
     * 逐行扫描，产出带小节路径的原子块。
     * <p>
     * 标题行本身不产出内容块，只推动标题栈；空行与水平线只做块分隔。
     */
    private List<Block> toBlocks(String markdown, String fallbackTitle) {
        List<String> lines = Arrays.asList(markdown.split("\n", -1));
        int[] offsets = lineOffsets(lines);
        HeadingStack stack = new HeadingStack(firstHeading(lines), fallbackTitle);

        List<Block> blocks = new ArrayList<>();
        boolean rootConsumed = false;
        int index = 0;

        while (index < lines.size()) {
            String line = lines.get(index);
            String stripped = line.strip();
            int tocEnd = isTocEntry(line) ? tocEnd(lines, index) : -1;

            if (tocEnd > index) {
                /* 无链接目录块：整块跳过，不产出内容 */
                index = tocEnd;
            } else if (stripped.isEmpty() || isThematicBreak(stripped) || isTocLabel(stripped)) {
                index++;
            } else if (isFenceLine(line)) {
                int end = fenceEnd(lines, index);
                blocks.add(toBlock(lines, offsets, index, end, stack.path(), BlockKind.FENCE));
                index = end + 1;
            } else if (HEADING.matcher(line).matches()) {
                /* 首个标题已作为合成根注入标题栈，此处跳过以免重复压栈 */
                if (rootConsumed) {
                    pushHeading(stack, line);
                }
                rootConsumed = true;
                index++;
            } else if (isTableRow(line)) {
                int end = runEnd(lines, index, MarkdownChunker::isTableRow);
                blocks.add(toBlock(lines, offsets, index, end, stack.path(), BlockKind.TABLE));
                index = end + 1;
            } else {
                int end = paragraphEnd(lines, index);
                blocks.add(toBlock(lines, offsets, index, end, stack.path(), BlockKind.TEXT));
                index = end + 1;
            }
        }
        return blocks;
    }

    /**
     * 解析标题行并压栈，解析失败则忽略。
     */
    private void pushHeading(HeadingStack stack, String line) {
        Heading heading = parseHeading(line);
        if (heading != null) {
            stack.push(heading);
        }
    }

    /**
     * 找出正文中第一个标题，围栏内的 # 注释不算标题。
     */
    private Heading firstHeading(List<String> lines) {
        boolean inFence = false;
        for (String line : lines) {
            if (isFenceLine(line)) {
                inFence = !inFence;
            } else if (!inFence) {
                Heading heading = parseHeading(line);
                if (heading != null) {
                    return heading;
                }
            }
        }
        return null;
    }

    /**
     * 解析标题行，非标题返回 null。标题文本去掉行内代码的反引号。
     */
    private static Heading parseHeading(String line) {
        Matcher matcher = HEADING.matcher(line);
        if (!matcher.matches()) {
            return null;
        }
        String title = matcher.group(2).replace("`", "").trim();
        return title.isEmpty() ? null : new Heading(matcher.group(1).length(), title);
    }

    /**
     * 找出与起始围栏配对的结束围栏；未闭合时取到文末。
     */
    private int fenceEnd(List<String> lines, int start) {
        for (int i = start + 1; i < lines.size(); i++) {
            if (isFenceLine(lines.get(i))) {
                return i;
            }
        }
        return lines.size() - 1;
    }

    /**
     * 从起始行往后取连续满足条件的行，返回末行下标。
     */
    private int runEnd(List<String> lines, int start, Predicate<String> belongs) {
        int i = start;
        while (i + 1 < lines.size() && belongs.test(lines.get(i + 1))) {
            i++;
        }
        return i;
    }

    /**
     * 段落结束位置：遇到空行、水平线、围栏、标题或表格即止。
     */
    private int paragraphEnd(List<String> lines, int start) {
        int i = start;
        while (i < lines.size() && isParagraphLine(lines.get(i))) {
            i++;
        }
        return Math.max(start, i - 1);
    }

    /**
     * 判断是否为普通正文行，即既能续接段落、又不属于其他块类型的行。
     */
    private boolean isParagraphLine(String line) {
        String stripped = line.strip();
        return !stripped.isEmpty()
                && !isThematicBreak(stripped)
                && !isFenceLine(line)
                && !isTableRow(line)
                && !HEADING.matcher(line).matches();
    }

    /**
     * 表格行判定。
     */
    private static boolean isTableRow(String line) {
        return line.strip().startsWith(String.valueOf(TABLE_MARKER));
    }

    /**
     * 目录条目判定：点分编号 + 标题，且长度不超过 {@link #TOC_ENTRY_MAX_LENGTH}。
     * <p>
     * 公开是为了让验收测试复用同一判定，避免正则出现两份实现。
     */
    public static boolean isTocEntry(String line) {
        return line.length() <= TOC_ENTRY_MAX_LENGTH && TOC_ENTRY.matcher(line).matches();
    }

    /**
     * 目录标记行判定，公开理由同 {@link #isTocEntry(String)}。
     */
    public static boolean isTocLabel(String stripped) {
        return TOC_LABEL.matcher(stripped).matches();
    }

    /**
     * 探测从 start 起的目录块，返回块之后的下标；条目数不足 {@link #MIN_TOC_ENTRIES} 时返回 -1。
     * <p>
     * 本批语料 46/141 个文件含无链接目录块——标题下直接罗列小节编号（如 "19.8.1. Where to Log"），
     * 条目间以空行分隔，部分带 ":   " 前缀或缩进。这些文字全是小节名、不含答案，但关键字与向量
     * 都极易命中：实测「hot standby 是什么」的首位命中就是 hot-standby.md 的目录块，
     * monitoring-stats.md 的首块更被 26 行章节名占满整个片位。故整块跳过。
     * <p>
     * 要求连续条目数达标，是为了不把正文里的编号列表误判为目录。
     */
    private int tocEnd(List<String> lines, int start) {
        int entries = 0;
        int end = start - 1;
        int cursor = start;
        while (cursor < lines.size()) {
            String line = lines.get(cursor);
            if (isTocEntry(line)) {
                entries++;
                end = cursor;
            } else if (!line.isBlank() && !isTocLabel(line.strip())) {
                break;
            }
            cursor++;
        }
        return entries >= MIN_TOC_ENTRIES ? end + 1 : -1;
    }

    /**
     * 水平线（--- / *** / ___）只作为块分隔，不产出内容。
     * <p>
     * 本批语料每个文件在 frontmatter 后与文末各有一条 "---"，若不识别会产出只含分隔符的噪声块。
     */
    private static boolean isThematicBreak(String stripped) {
        if (stripped.length() < 3) {
            return false;
        }
        char first = stripped.charAt(0);
        if (first != '-' && first != '*' && first != '_') {
            return false;
        }
        return stripped.chars().allMatch(c -> c == first);
    }

    /**
     * 按行区间构造原子块。
     */
    private Block toBlock(List<String> lines, int[] offsets, int start, int end, String path, BlockKind kind) {
        String text = String.join("\n", lines.subList(start, end + 1));
        return new Block(text, path, offsets[start], offsets[end] + lines.get(end).length(), kind, 0);
    }

    /**
     * 预算每行的起始字符偏移，用于回填 chunk 的原文位置。
     */
    private int[] lineOffsets(List<String> lines) {
        int[] offsets = new int[lines.size()];
        int cursor = 0;
        for (int i = 0; i < lines.size(); i++) {
            offsets[i] = cursor;
            cursor += lines.get(i).length() + 1;
        }
        return offsets;
    }

    /* ==================== 第二阶段：把原子块切成片段 ==================== */

    /**
     * 把原子块切成可直接成块的片段。
     * <p>
     * 片段保留原顺序，{@link Block#fragmentIndex} 大于 0 表示它是某个原子块被切开后的后续片段：
     * 这类片段自成一个 chunk，并会带上前一片段末尾的重叠区，而原子块的第一段不带。
     */
    private List<Block> expand(List<Block> blocks) {
        List<Block> result = new ArrayList<>();
        for (Block block : blocks) {
            result.addAll(splitAtomic(block));
        }
        return result;
    }

    /**
     * 按类型切分单个原子块，未超限的整块原样返回。
     * <p>
     * 两类块的阈值不同：代码与表格是结构完整的整体，被切开就失去可读性（表格还要补表头），
     * 因此容忍到 {@link #HARD_MAX_CHUNK_SIZE}；正文没有这层负担，超过 {@link #MAX_CHUNK_SIZE} 就该切。
     */
    private List<Block> splitAtomic(Block block) {
        int limit = block.kind == BlockKind.TEXT ? MAX_CHUNK_SIZE : HARD_MAX_CHUNK_SIZE;
        if (block.text.length() <= limit) {
            return List.of(block);
        }
        return block.kind == BlockKind.TEXT ? splitText(block) : splitLines(block);
    }

    /**
     * 按行贪心切分超长代码块 / 表格。
     * <p>
     * 代码围栏先剥掉原围栏行，再为每个片段补回 ``` 包裹，使片段仍是语法合法的代码块；
     * 表格为第二个及之后的片段补回表头两行（列名 + 分隔行），否则片段只剩数据行，
     * 既读不出各列的含义，也不再是合法的 markdown 表格（渲染不出表格）。
     * <p>
     * 任何一行都整行保留，不做截断：本批 functions-admin.md 与 monitoring-stats.md 的表格里
     * 存在单行上千字符的单元格（最长 2211），截断会直接丢内容，故宁可让该片段超过
     * {@link #MAX_CHUNK_SIZE}。表格片段的取词预算会扣掉表头宽度，使片段总长仍在目标附近。
     */
    private List<Block> splitLines(Block block) {
        List<String> rows = new ArrayList<>(Arrays.asList(block.text.split("\n", -1)));
        boolean fence = block.kind == BlockKind.FENCE && rows.size() >= 2;
        if (fence) {
            rows = rows.subList(1, rows.size() - 1);
        }
        List<String> header = tableHeader(block, rows);
        int budget = MAX_CHUNK_SIZE - headerWidth(header);

        List<Block> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int cursor = block.start;
        for (String row : rows) {
            if (buffer.length() > 0 && buffer.length() + row.length() + 1 > budget) {
                result.add(slice(block, buffer.toString(), cursor, result.size(), fence, header));
                cursor += buffer.length() + 1;
                buffer.setLength(0);
            }
            if (buffer.length() > 0) {
                buffer.append('\n');
            }
            buffer.append(row);
        }
        if (buffer.length() > 0) {
            result.add(slice(block, buffer.toString(), cursor, result.size(), fence, header));
        }
        return result;
    }

    /**
     * 按标点递归切分超长正文，再把碎片贪心拼回接近目标大小的片段。
     * <p>
     * 切点留在标点之后，因此拼回去与原文逐字符一致；标点后必须跟空白或到文末，
     * 否则会把 "PostgreSQL 18.0"、"1,024" 这类数字切断（判定见 {@link #endsClause(String, int)}）。
     */
    private List<Block> splitText(Block block) {
        List<String> merged = mergePieces(splitRecursive(block.text, TEXT_SEPARATORS));
        List<Block> result = new ArrayList<>();
        int cursor = block.start;
        for (int i = 0; i < merged.size(); i++) {
            String text = merged.get(i);
            result.add(new Block(text, block.sectionPath, cursor, cursor + text.length(), BlockKind.TEXT, i));
            cursor += text.length();
        }
        return result;
    }

    /**
     * 递归切分：当前分隔符切不动（片段内不含该标点）时退到下一级分隔符；
     * 分隔符用尽仍有超长片段时原样返回——宁可留一个超长片段，也不做字符级硬切。
     */
    private List<String> splitRecursive(String text, List<String> separators) {
        if (text.length() < MAX_CHUNK_SIZE || separators.isEmpty()) {
            return List.of(text);
        }
        List<String> pieces = splitAfter(text, separators.get(0));
        if (pieces.size() == 1) {
            return splitRecursive(text, separators.subList(1, separators.size()));
        }
        List<String> result = new ArrayList<>();
        for (String piece : pieces) {
            result.addAll(splitRecursive(piece, separators));
        }
        return result;
    }

    /**
     * 在每处句读标点之后切开，标点归前一段，段间空白归后一段，故拼接后与原文完全一致。
     */
    private static List<String> splitAfter(String text, String separator) {
        List<String> pieces = new ArrayList<>();
        int from = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == separator.charAt(0) && endsClause(text, i)) {
                pieces.add(text.substring(from, i + 1));
                from = i + 1;
            }
        }
        if (from < text.length()) {
            pieces.add(text.substring(from));
        }
        return pieces.isEmpty() ? List.of(text) : pieces;
    }

    /**
     * 标点之后紧跟空白或已到文末才算句读，用于避开小数点与千分位逗号。
     */
    private static boolean endsClause(String text, int index) {
        return index + 1 >= text.length() || Character.isWhitespace(text.charAt(index + 1));
    }

    /**
     * 把句子级碎片贪心拼回接近 {@link #MAX_CHUNK_SIZE} 的片段。
     * <p>
     * 拼接到下一片会超出目标大小为止，因此中间的碎片不会剩下孤零零的一小段；
     * 末尾的碎片另按 {@link #foldTail(List)} 处理。
     */
    private static List<String> mergePieces(List<String> pieces) {
        List<String> merged = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        for (String piece : pieces) {
            if (buffer.length() > 0 && buffer.length() + piece.length() > MAX_CHUNK_SIZE) {
                merged.add(buffer.toString());
                buffer.setLength(0);
            }
            buffer.append(piece);
        }
        if (buffer.length() > 0) {
            merged.add(buffer.toString());
        }
        return foldTail(merged);
    }

    /**
     * 尾段过小时并入前一段：句末标点切出的最后一段常只剩一两句，
     * 单独成块既缺上下文又占片位，前提是并入后仍不超目标大小。
     */
    private static List<String> foldTail(List<String> merged) {
        int last = merged.size() - 1;
        if (last < 1) {
            return merged;
        }
        String tail = merged.get(last);
        String previous = merged.get(last - 1);
        if (tail.length() < MIN_CHUNK_SIZE && previous.length() + tail.length() <= MAX_CHUNK_SIZE) {
            merged.set(last - 1, previous + tail);
            merged.remove(last);
        }
        return merged;
    }

    /**
     * 取表格需要复用的表头两行：首行（列名）与紧跟的分隔行。
     * <p>
     * 非表格、不足两行、或第二行不是分隔行时返回空——后者说明这不是规范表格，
     * 补一个伪表头只会误导，不如只靠面包屑提供上下文。
     */
    private static List<String> tableHeader(Block block, List<String> rows) {
        if (block.kind != BlockKind.TABLE || rows.size() < 2
                || !TABLE_SEPARATOR.matcher(rows.get(1).strip()).matches()) {
            return List.of();
        }
        return List.of(rows.get(0), rows.get(1));
    }

    /**
     * 表头两行拼接后的宽度（含与正文之间的换行），用于从取词预算中扣除。
     */
    private static int headerWidth(List<String> header) {
        return header.isEmpty() ? 0 : String.join("\n", header).length() + 1;
    }

    /**
     * 构造片段：代码围栏片段补回围栏标记，第二个及之后的表格片段补回表头。
     * <p>
     * 偏移取该片段**原始行**在归一化正文中的位置，不含补出来的表头与围栏：
     * 表头、围栏是片段自己长出来的，算进长度会让 end 比真实位置多出一个表头的宽度。
     */
    private Block slice(Block block, String body, int start, int fragmentIndex, boolean fence, List<String> header) {
        StringBuilder text = new StringBuilder();
        if (fragmentIndex > 0 && !header.isEmpty()) {
            text.append(String.join("\n", header)).append('\n');
        }
        text.append(fence ? FENCE_MARKER + "\n" + body + "\n" + FENCE_MARKER : body);
        int rowStart = fragmentIndex == 0 && fence ? start + FENCE_MARKER.length() + 1 : start;
        return new Block(text.toString(), block.sectionPath, rowStart, rowStart + body.length(),
                block.kind, fragmentIndex);
    }

    /* ==================== 第三阶段：打包成块 ==================== */

    /**
     * 把片段按大小与小节边界贪心打包。
     */
    private List<Fragment> pack(List<Block> blocks) {
        List<Fragment> fragments = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        List<String> paths = new ArrayList<>();
        String dominantPath = null;
        int dominantLength = 0;
        int start = 0;
        int end = 0;
        boolean overlap = false;

        for (Block block : blocks) {
            if (shouldBreak(buffer, dominantPath, block)) {
                fragments.add(new Fragment(buffer.toString(), label(paths, dominantPath),
                        start, end, overlap));
                buffer.setLength(0);
                paths.clear();
                dominantLength = 0;
            }
            if (buffer.length() == 0) {
                start = block.start;
                overlap = needsOverlap(block);
            } else {
                buffer.append('\n');
            }
            if (!paths.contains(block.sectionPath)) {
                paths.add(block.sectionPath);
            }
            if (block.text.length() >= dominantLength) {
                dominantPath = block.sectionPath;
                dominantLength = block.text.length();
            }
            buffer.append(block.text);
            end = block.end;
        }
        if (buffer.length() > 0) {
            fragments.add(new Fragment(buffer.toString(), label(paths, dominantPath), start, end, overlap));
        }
        return fragments;
    }

    /**
     * 该片段的面包屑：只含一个小节时就是它自己，合并了多个小节时分两种情况。
     * <p>
     * 短小节（本批 42 个小节正文不足 200 字）会被并入下一个小节的块，这有两种形态：
     * <ol>
     *     <li><b>嵌套合并</b>（如 {@code VACUUM} 的导语并入 {@code VACUUM > Synopsis}）：内容全在深侧小节的
     *     子树里，取最深的那个即可——这层名字正是分块时要保住的东西，退到根反而让同文档的块长得一模一样；</li>
     *     <li><b>并列合并</b>（如 {@code … > Notes} 与 {@code … > Examples}）：没有哪个小节盖得住两边，
     *     标任何一边都会张冠李戴，只能退到公共父级。</li>
     * </ol>
     *
     * @param paths        该片段内出现过的全部小节路径，按出现顺序去重
     * @param fallbackPath 兜底（{@code paths} 非空时用不到）
     */
    private static String label(List<String> paths, String fallbackPath) {
        if (paths.isEmpty()) {
            return fallbackPath;
        }
        for (String candidate : paths) {
            boolean covering = paths.stream().allMatch(path -> candidate.equals(path)
                    || candidate.startsWith(path + SECTION_SEPARATOR));
            if (covering) {
                return candidate;
            }
        }
        return commonAncestor(paths);
    }

    /**
     * 各小节路径的公共父级，如 {@code A > B} 与 {@code A > C} 得到 {@code A}。
     * <p>
     * 标题栈底是合成根，所有路径必然共享它，因此结果不会为空。
     */
    private static String commonAncestor(List<String> paths) {
        List<String> first = List.of(paths.get(0).split(SECTION_SEPARATOR));
        int shared = first.size();
        for (String path : paths) {
            List<String> parts = List.of(path.split(SECTION_SEPARATOR));
            int count = 0;
            while (count < shared && count < parts.size() && first.get(count).equals(parts.get(count))) {
                count++;
            }
            shared = count;
        }
        return String.join(SECTION_SEPARATOR, first.subList(0, Math.max(1, shared)));
    }

    /**
     * 该片段是否需要重叠区：只有被切开的<b>正文</b>块的后续片段需要。
     * <p>
     * 表格片段补回表头、代码片段补回围栏，本身就带着跨片段的锚点；再从上一片段末尾搬一段文字过去
     * 只会注入噪声——表行没有表头是读不出列含义的，而长单元格里按句切出的一段（如
     * "since otherwise there is no ... |"）看起来又不以 | 开头，靠形态根本拦不住。
     */
    private static boolean needsOverlap(Block block) {
        return block.fragmentIndex > 0 && block.kind == BlockKind.TEXT;
    }

    /**
     * 判断是否应在当前片段之前断开。
     */
    private boolean shouldBreak(StringBuilder buffer, String path, Block block) {
        if (buffer.length() == 0) {
            return false;
        }
        if (block.fragmentIndex > 0) {
            /* 原子块被切开后的后续片段自成一个块：它们本就是为控制大小切出来的，再拼回去等于白切 */
            return true;
        }
        if (buffer.length() + block.text.length() + 1 > MAX_CHUNK_SIZE) {
            return true;
        }
        return !block.sectionPath.equals(path) && buffer.length() >= MIN_CHUNK_SIZE;
    }

    /* ==================== 第四阶段：补重叠区与面包屑 ==================== */

    /**
     * 把打包结果转成 chunk：被切开正文块的后续片段前置上一片段末尾的重叠区，
     * 所有块前置小节面包屑。
     * <p>
     * 重叠区只加在同一原子块的相邻片段之间，原子块的第一段不加：跨原子块拼接会把
     * 上一块的收尾内容注进下一块的正文，得不偿失。
     */
    private List<ChunkDraft> assemble(List<Fragment> fragments) {
        List<ChunkDraft> drafts = new ArrayList<>();
        for (Fragment fragment : fragments) {
            String text = fragment.text();
            if (fragment.overlap() && !drafts.isEmpty()) {
                String overlap = tail(drafts.get(drafts.size() - 1).getText());
                if (!overlap.isEmpty()) {
                    text = overlap + "\n" + text;
                }
            }
            String path = fragment.sectionPath() == null ? DEFAULT_ROOT : fragment.sectionPath();
            drafts.add(new ChunkDraft(path + BREADCRUMB_SEPARATOR + text,
                    fragment.sectionPath(), fragment.start(), fragment.end()));
        }
        return drafts;
    }

    /**
     * 取上一片段末尾至多 {@link #OVERLAP_SIZE} 个字符作为重叠区，取不到合适起点时返回空串。
     * <p>
     * 起点对齐到行首或标点之后，避免注入半截词句：本批语料 72% 的正文块本身就是单行长段落，
     * 若只按整行取，重叠区几乎永远是空的。
     */
    private static String tail(String previous) {
        String body = previous.stripTrailing();
        int start = nextBoundary(body, Math.max(0, body.length() - OVERLAP_SIZE));
        return start >= body.length() ? "" : body.substring(start).stripLeading();
    }

    /**
     * 从 from 起找第一个干净的起点：行首，或句读标点之后的空白处；找不到则返回文末（放弃重叠）。
     */
    private static int nextBoundary(String body, int from) {
        if (from == 0) {
            return 0;
        }
        for (int i = from; i < body.length(); i++) {
            char before = body.charAt(i - 1);
            if (before == '\n' || ((before == '.' || before == ',') && Character.isWhitespace(body.charAt(i)))) {
                return i;
            }
        }
        return body.length();
    }

    /* ==================== 内部结构 ==================== */

    /**
     * 原子块类型，决定超长时如何细分。
     */
    private enum BlockKind {
        /** 代码围栏，细分后需补回围栏标记 */
        FENCE,
        /** 表格 */
        TABLE,
        /** 普通正文 */
        TEXT
    }

    /**
     * 内容单元：代码围栏、表格，或一组连续正文行。
     */
    private static final class Block {

        private final String text;
        private final String sectionPath;
        private final int start;
        private final int end;
        private final BlockKind kind;

        /** 同一原子块内的片段序号，0 表示它就是原子块本身或首段 */
        private final int fragmentIndex;

        private Block(String text, String sectionPath, int start, int end, BlockKind kind, int fragmentIndex) {
            this.text = text;
            this.sectionPath = sectionPath;
            this.start = start;
            this.end = end;
            this.kind = kind;
            this.fragmentIndex = fragmentIndex;
        }
    }

    /**
     * 打包结果：一个 chunk 的正文与归属。
     *
     * @param overlap 该块以被切开正文块的后续片段开头，需要补上一片段的重叠区
     */
    private record Fragment(String text, String sectionPath, int start, int end, boolean overlap) {
    }

    /**
     * 标题层级。
     */
    private static final class Heading {

        private final int level;
        private final String title;

        private Heading(int level, String title) {
            this.level = level;
            this.title = title;
        }
    }

    /**
     * 标题栈：维护当前小节路径。
     * <p>
     * 本批语料 133/141 个文件没有 H1（正文直接从 "## 章节名" 开始），因此把正文首个标题
     * 作为<b>合成根</b>压入栈底且永不弹出；之后所有同级或更高级标题都视为它的子节。
     * 这样 "## Synopsis" 得到 "VACUUM &gt; Synopsis"，而不是丢失文档身份的 "Synopsis"。
     */
    private static final class HeadingStack {

        private final int rootLevel;
        private final Deque<Heading> stack = new ArrayDeque<>();

        private HeadingStack(Heading first, String fallbackTitle) {
            if (first == null) {
                this.rootLevel = 0;
                stack.push(new Heading(0, fallbackTitle == null || fallbackTitle.isBlank()
                        ? DEFAULT_ROOT : fallbackTitle));
            } else {
                this.rootLevel = first.level;
                stack.push(new Heading(0, first.title));
            }
        }

        /**
         * 压入标题：同级或更高级的标题先弹出，栈底根节点始终保留。
         */
        private void push(Heading heading) {
            int level = heading.level <= rootLevel ? rootLevel + 1 : heading.level;
            while (stack.size() > 1 && stack.peek().level >= level) {
                stack.pop();
            }
            stack.push(new Heading(level, heading.title));
        }

        /**
         * 当前小节路径，自栈底向栈顶拼接。
         */
        private String path() {
            List<String> parts = new ArrayList<>();
            Iterator<Heading> iterator = stack.descendingIterator();
            while (iterator.hasNext()) {
                parts.add(iterator.next().title);
            }
            return String.join(SECTION_SEPARATOR, parts);
        }
    }
}
