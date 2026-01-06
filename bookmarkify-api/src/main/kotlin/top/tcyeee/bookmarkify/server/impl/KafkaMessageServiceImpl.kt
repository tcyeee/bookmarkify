package top.tcyeee.bookmarkify.server.impl

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.enums.KafkaTopicType
import top.tcyeee.bookmarkify.server.IKafkaMessageService

@Service
@ConditionalOnProperty(prefix = "bookmarkify.kafka", name = ["enabled"], havingValue = "true")
class KafkaMessageServiceImpl(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) : IKafkaMessageService {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(topic: KafkaTopicType, message: String) {
        kafkaTemplate.send(topic.name, message).whenComplete { result, ex ->
            if (ex != null) {
                log.error("Kafka send failed topic={} message={}", topic, message, ex)
                return@whenComplete
            }
            val metadata = result.recordMetadata
            log.info(
                "Kafka send success topic={} partition={} offset={}",
                metadata.topic(),
                metadata.partition(),
                metadata.offset()
            )
        }
    }
}

