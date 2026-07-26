package com.library.agent.MQ.producer;

import com.library.agent.MQ.Message.RagIngestMessage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

/**
 * RAG 入库消息生产者。
 * <p>
 * 在发送消息前将 W3C traceparent 注入消息头，
 * Consumer 侧可据此恢复 Trace 上下文实现跨进程链路追踪。
 */
@Service
@RequiredArgsConstructor
public class RagIngestProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final Tracer tracer;

    public void send(RagIngestMessage message) {
        Span currentSpan = tracer.currentSpan();
        String traceparent = null;
        String conversationId = null;

        if (currentSpan != null) {
            traceparent = String.format("00-%s-%s-01",
                    currentSpan.context().traceId(),
                    currentSpan.context().spanId());
        }

        rocketMQTemplate.convertAndSend("rag-ingest-topic",
                MessageBuilder.withPayload(message)
                        .setHeader("traceparent", traceparent)
                        .build()
        );
    }
}
