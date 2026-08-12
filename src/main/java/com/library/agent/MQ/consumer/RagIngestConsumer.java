package com.library.agent.MQ.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.agent.MQ.Message.RagIngestMessage;
import com.library.agent.MQ.processor.RagAsyncProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Service;

/**
 * RAG 入库消息消费者。
 */
@Slf4j
@Service
@RocketMQMessageListener(
        topic = "rag-ingest-topic",
        consumerGroup = "agent-rag-consumer-group"
)
@RequiredArgsConstructor
public class RagIngestConsumer implements RocketMQListener<MessageExt> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RagAsyncProcessor ragAsyncProcessor;

    @Override
    public void onMessage(MessageExt messageExt) {
        try {
            RagIngestMessage message = OBJECT_MAPPER.readValue(
                    messageExt.getBody(), RagIngestMessage.class);
            ragAsyncProcessor.process(message);
        } catch (Exception e) {
            log.warn("RocketMQ 消费异常, msgId={}", messageExt.getMsgId(), e);
            throw new RuntimeException("RAG 异步消费失败", e);
        }
    }
}
