package com.library.agent.MQ.consumer;

import com.library.agent.MQ.Message.RagIngestMessage;
import com.library.agent.MQ.processor.RagAsyncProcessor;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Service;

@Service
@RocketMQMessageListener(
        topic = "rag-ingest-topic",
        consumerGroup = "agent-rag-consumer-group"
)
@RequiredArgsConstructor
public class RagIngestConsumer implements RocketMQListener<RagIngestMessage> {

    private final RagAsyncProcessor ragAsyncProcessor;

    @Override
    public void onMessage(RagIngestMessage message) {
        ragAsyncProcessor.process(message);
    }
}