package com.library.agent.eval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.agent.eval.dto.RagQueryGenRequest;
import com.library.agent.eval.dto.RagQueryGenResponse;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG 测评测试 query 的反向生成服务。
 * <p>
 * 与 Judge 共用 {@code judgeChatModel}（temperature 固定 0.0），不新增模型配置：
 * 生成的是「候选」提问，最终是否采用由人工复核决定，因此这里只负责产出与解析，不做质量判定。
 */
@Slf4j
@Service
public class RagQueryGenService {

    /**
     * 出题提示词模板的 classpath 路径
     */
    private static final String PROMPT_PATH = "Prompt/eval/RagQueryGenPrompt.md";

    /**
     * 出题提示词版本号；提示词改动时必须同步递增，否则不同批次生成的候选不可比
     */
    public static final String QUERY_GEN_PROMPT_VERSION = "rag-querygen-v1";

    /**
     * 面包屑与正文之间的分隔空行，与 {@code MarkdownChunker.BREADCRUMB_SEPARATOR} 一致
     */
    private static final String BREADCRUMB_SEPARATOR = "\n\n";

    private final ChatModel judgeChatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String promptTemplate;

    public RagQueryGenService(@Qualifier("judgeChatModel") ChatModel judgeChatModel) {
        this.judgeChatModel = judgeChatModel;
        this.promptTemplate = loadPrompt();
    }

    /**
     * 根据一段文档片段生成 5 类候选提问。
     *
     * @param request 片段正文与小节路径
     * @return 候选提问与实际使用的模型名
     * @throws ResponseStatusException 片段为空时 400；模型输出无法解析时 502
     */
    public RagQueryGenResponse generate(RagQueryGenRequest request) {
        if (request == null || request.getChunkText() == null || request.getChunkText().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "chunkText 不能为空");
        }

        String prompt = buildPrompt(request);
        ChatResponse response = judgeChatModel.chat(ChatRequest.builder()
                .messages(List.of(UserMessage.from(prompt)))
                .build());

        String output = response.aiMessage() == null ? null : response.aiMessage().text();
        RagQueryGenResponse result = new RagQueryGenResponse();
        result.setQueries(parseQueries(output));
        result.setModel(response.modelName());
        return result;
    }

    /**
     * 渲染出题提示词。正文头部的面包屑是小节路径的复制，
     * 不剥掉的话模型会直接照抄路径来出题。
     */
    private String buildPrompt(RagQueryGenRequest request) {
        return promptTemplate
                .replace("{{file_name}}", blankToPlaceholder(request.getFileName()))
                .replace("{{section_path}}", blankToPlaceholder(request.getSectionPath()))
                .replace("{{chunk_text}}", stripBreadcrumb(request.getChunkText(), request.getSectionPath()));
    }

    /**
     * 剥掉正文开头的面包屑前缀（小节路径 + 空行），保留纯正文。
     */
    private String stripBreadcrumb(String chunkText, String sectionPath) {
        if (sectionPath == null || sectionPath.isBlank()) {
            return chunkText;
        }
        String prefix = sectionPath + BREADCRUMB_SEPARATOR;
        return chunkText.startsWith(prefix) ? chunkText.substring(prefix.length()) : chunkText;
    }

    /**
     * 解析模型输出的候选提问，容忍误加的 Markdown 代码块。
     */
    private List<RagQueryGenResponse.Item> parseQueries(String output) {
        String json = extractJson(output);
        if (json == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "出题模型未返回可解析的 JSON");
        }
        try {
            JsonNode array = objectMapper.readTree(json).path("queries");
            if (!array.isArray() || array.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "出题模型未返回任何提问");
            }
            List<RagQueryGenResponse.Item> items = new ArrayList<>();
            for (JsonNode node : array) {
                String query = node.path("query").asText("");
                if (query.isBlank()) {
                    continue;
                }
                RagQueryGenResponse.Item item = new RagQueryGenResponse.Item();
                item.setQuery(query);
                item.setType(node.path("type").asText("unknown"));
                items.add(item);
            }
            if (items.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "出题模型返回的提问均为空");
            }
            return items;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to parse query generation output: {}", output, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "出题模型输出解析失败");
        }
    }

    /**
     * 从模型输出中截取首个 JSON 对象（去掉可能存在的 ```json 包裹与前后噪声）。
     */
    private String extractJson(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        return (start < 0 || end <= start) ? null : output.substring(start, end + 1);
    }

    private String blankToPlaceholder(String value) {
        return value == null || value.isBlank() ? "（未提供）" : value;
    }

    /**
     * 从 classpath 读取提示词模板，读取失败直接抛异常阻断启动，避免用空提示词静默出题。
     */
    private String loadPrompt() {
        try (InputStream input = new ClassPathResource(PROMPT_PATH).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load query gen prompt: " + PROMPT_PATH, e);
        }
    }
}
