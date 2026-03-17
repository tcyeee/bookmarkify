package top.tcyeee.bookmarkify.config.kafka

import org.apache.kafka.clients.admin.NewTopic
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

/**
 * 确保当前环境所使用的 Kafka topic 在启动时已存在。
 * 若 broker 禁用了 auto.create.topics.enable，topic 不存在时：
 *  - 生产者发送失败（异常被 whenComplete 回调吞掉，无感知）
 *  - 消费者无法订阅，始终收不到消息
 * 通过 KafkaAdmin + NewTopic bean，Spring 在启动阶段通过 Admin API 主动创建，
 * 不依赖 broker 配置，dev（BOOKMARKIFY_DEV）和 prod（BOOKMARKIFY）均适用。
 */
@Configuration
class KafkaTopicConfig(
    @Value("\${bookmarkify.kafka.topic}") private val topic: String,
) {
    @Bean
    fun bookmarkifyTopic(): NewTopic = TopicBuilder.name(topic).partitions(1).replicas(1).build()
}
