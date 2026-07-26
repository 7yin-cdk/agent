package com.library.agent.MQ.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.agent.MQ.Message.RagIngestMessage;
import com.library.agent.MQ.processor.RagAsyncProcessor;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Service;

/**
 * RAG 入库消息消费者。
 * <p>
 * 从消息属性中提取 W3C traceparent 并创建父 Span，
 * 使得 MQ 消费端的追踪与 HTTP 请求端的追踪共享同一个 traceId。
 */
@Slf4j
@Service
@RocketMQMessageListener(
        topic = "rag-ingest-topic",
        consumerGroup = "agent-rag-consumer-group"
)
@RequiredArgsConstructor
public class RagIngestConsumer implements RocketMQListener<MessageExt> {

    private static final String TRACEPARENT_KEY = "traceparent";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RagAsyncProcessor ragAsyncProcessor;
    private final Tracer tracer;

    @Override
    public void onMessage(MessageExt messageExt) {
        String traceparent = messageExt.getProperty(TRACEPARENT_KEY);
        Span parentSpan = null;

        if (traceparent != null && !traceparent.isBlank()) {
            parentSpan = createSpanFromTraceparent(traceparent);
        } else {
            parentSpan = tracer.nextSpan()
                    .name("mq.consume rag-ingest-topic")
                    .start();
        }

        try (Tracer.SpanInScope ignored = tracer.withSpan(parentSpan)) {
            parentSpan.tag("messaging.system", "rocketmq");
            parentSpan.tag("messaging.destination", "rag-ingest-topic");
            parentSpan.tag("messaging.message_id", messageExt.getMsgId());

            RagIngestMessage message = OBJECT_MAPPER.readValue(
                    messageExt.getBody(), RagIngestMessage.class);
            ragAsyncProcessor.process(message);

        } catch (Exception e) {
            log.warn("RocketMQ 消费异常, msgId={}", messageExt.getMsgId(), e);
            parentSpan.error(e);
            throw new RuntimeException("RAG 异步消费失败", e);
        } finally {
            parentSpan.end();
        }
    }

    /**
     * 从 W3C traceparent 字符串解析并创建 Span。
     * <p>
     * traceparent 格式：00-{traceId}-{spanId}-01。
     * 消费端创建独立根 Span，通过标签关联上游 traceId 和 spanId，
     * 在 Jaeger 中可通过上游 traceId 搜索到消费端的 Trace。
     */
    private Span createSpanFromTraceparent(String traceparent) {
        Span span = tracer.nextSpan()
                .name("mq.consume rag-ingest-topic")
                .start();

        String[] parts = traceparent.split("-");
        if (parts.length >= 4) {
            span.tag("messaging.rocketmq.parent_trace_id", parts[1]);
            span.tag("messaging.rocketmq.parent_span_id", parts[2]);
        }

        return span;
    }
}
