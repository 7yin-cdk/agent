package com.library.agent.mail;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 邮件发送服务。
 * <p>
 * 基于 Spring 的 JavaMailSender 封装 HTML 邮件发送，
 * 供告警邮件工具与规则兜底逻辑复用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailService {

    private final JavaMailSender mailSender;

    /**
     * 发件人邮箱，取自 SMTP 登录账号。
     * QQ 等邮箱要求信封发件人（MAIL FROM）必须等于授权登录账号，因此 From 头必须显式设置。
     */
    @Value("${spring.mail.username}")
    private String fromAddress;

    /**
     * 向多个收件人发送一封 HTML 邮件。
     *
     * @param recipients 收件人邮箱列表
     * @param subject    邮件主题
     * @param htmlBody   HTML 邮件正文
     * @return true 表示发送成功
     */
    public boolean sendHtml(java.util.List<String> recipients, String subject, String htmlBody) {
        if (recipients == null || recipients.isEmpty()) {
            log.warn("邮件收件人为空，跳过发送 subject={}", subject);
            return false;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(fromAddress);
            helper.setTo(recipients.toArray(new String[0]));
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            mailSender.send(message);
            log.info("告警邮件发送成功 subject={}, recipients={}", subject, recipients);
            return true;
        } catch (Exception e) {
            log.error("告警邮件发送失败 subject={}", subject, e);
            return false;
        }
    }
}
