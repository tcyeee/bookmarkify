package top.tcyeee.bookmarkify.config.kafka

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.server.IBookmarkService

@Component
@ConditionalOnProperty(prefix = "bookmarkify.kafka", name = ["enabled"], havingValue = "true")
class KafkaMessageListener(
    private val bookmarkService: IBookmarkService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${bookmarkify.kafka.default-topic:bookmarkify-events}"],
        groupId = "\${spring.kafka.consumer.group-id:bookmarkify-group}"
    )
    fun onMessage(record: ConsumerRecord<String, BookmarkEntity>) {
        log.info("[Kafka] received topic=${record.topic()}")

    }
}




