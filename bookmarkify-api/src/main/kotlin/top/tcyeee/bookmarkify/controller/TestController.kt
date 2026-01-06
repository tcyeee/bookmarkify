package top.tcyeee.bookmarkify.controller

import cn.dev33.satoken.annotation.SaIgnore
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.config.entity.KafkaProperties
import top.tcyeee.bookmarkify.server.IKafkaMessageService


/**
 * 测试类
 *
 * @author tcyeee
 * @date 3/9/25 19:53
 */
@RestController
@Tag(name = "测试相关")
@RequestMapping("/test")
class TestController(
    private val kafkaMessageService: ObjectProvider<IKafkaMessageService>,
    private val kafkaProperties: KafkaProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @SaIgnore
    @GetMapping("/link")
    fun linkTest(type: Int, param: String?): Boolean {
        return true
    }

    /**
     * 最快验证 Kafka：若未开启直接提示；已开启则发一条消息并返回结果。
     */
    @SaIgnore
    @GetMapping("/kafka/ping")
    fun kafkaPing(): String {
        if (!kafkaProperties.enabled) {
            return "kafka disabled by config"
        }
        val svc = kafkaMessageService.ifAvailable
            ?: return "kafka bean missing (check enabled/config)"

        return try {
            svc.send("kafka-ping", "ping-${System.currentTimeMillis()}")
            "kafka send ok"
        } catch (ex: Exception) {
            log.error("Kafka ping failed", ex)
            "kafka send failed: ${ex.message}"
        }
    }
}