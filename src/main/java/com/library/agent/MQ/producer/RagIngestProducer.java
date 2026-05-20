package MQ.producer;

import MQ.Message.RagIngestMessage;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RagIngestProducer {

    private final RocketMQTemplate rocketMQTemplate;

    public void send(RagIngestMessage message) {
        rocketMQTemplate.convertAndSend("rag-ingest-topic", message);
    }
}
