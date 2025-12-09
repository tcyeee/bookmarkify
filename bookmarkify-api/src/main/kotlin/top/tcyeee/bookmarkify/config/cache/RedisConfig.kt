package top.tcyeee.bookmarkify.config.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer

/**
 * @author tcyeee
 * @date 4/15/25 20:39
 */
@Configuration
class RedisConfig {
    @Bean
    @Primary
    fun redisTemplate(redisConnectionFactory: RedisConnectionFactory) = RedisTemplate<String, Any>().apply {
        val objectMapper = jacksonObjectMapper().apply {
            registerModule(JavaTimeModule())
            activateDefaultTyping(
                BasicPolymorphicTypeValidator.builder()
                    .allowIfSubType(Object::class.java)
                    .build(),
                ObjectMapper.DefaultTyping.EVERYTHING
            )
        }

        val serializer = GenericJackson2JsonRedisSerializer(objectMapper)
        connectionFactory = redisConnectionFactory
        keySerializer = StringRedisSerializer()
        valueSerializer = serializer
        hashKeySerializer = StringRedisSerializer()
        hashValueSerializer = serializer
    }
}