package com.library.agent.MQ.producer;

import com.library.agent.MQ.Message.RagIngestMessage;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;

/**
 * RAG 入库消息生产者。
 */
@Service
@RequiredArgsConstructor
public class RagIngestProducer {

    private final RocketMQTemplate rocketMQTemplate;

    public void send(RagIngestMessage message) {
        rocketMQTemplate.convertAndSend("rag-ingest-topic", message);
    }
}
