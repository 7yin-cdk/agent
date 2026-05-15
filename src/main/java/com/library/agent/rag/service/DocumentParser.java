package com.library.agent.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;

@Slf4j
@Component
public class DocumentParser {

    private final Tika tika = new Tika();

    /**
     * 文档解析为纯文本（仅支持可解析文档）
     */
    public String parse(MultipartFile file) {
        try (InputStream inputStream = file.getInputStream()) {

            Metadata metadata = new Metadata();
            String content = tika.parseToString(inputStream, metadata);

            log.info("Tika解析完成，长度: {}", content.length());

            // 核心校验：防止扫描PDF
            if (content == null || content.trim().length() < 50) {
                throw new RuntimeException("文件解析失败：该文件可能为扫描版PDF或不支持的格式");
            }

            return cleanText(content);

        } catch (Exception e) {
            log.error("文档解析失败", e);
            throw new RuntimeException("文档解析失败：" + e.getMessage());
        }
    }

    /**
     * 文本清洗（RAG关键步骤）
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