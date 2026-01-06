package top.tcyeee.bookmarkify.config.entity

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "bookmarkify.kafka")
data class KafkaProperties(
    var enabled: Boolean = false,
    var defaultTopic: String = "bookmarkify-events",
    var consumerGroup: String = "bookmarkify-group"
)

