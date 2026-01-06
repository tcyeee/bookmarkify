package top.tcyeee.bookmarkify.controller

import cn.dev33.satoken.annotation.SaIgnore
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.enums.KafkaTopicType
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
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @SaIgnore
    @GetMapping("/link")
    fun linkTest(): Boolean {
        val svc = kafkaMessageService.ifAvailable ?: throw CommonException(ErrorType.E999)

        try {
            svc.send(KafkaTopicType.DEFAULT, "ping-${System.currentTimeMillis()}")
        } catch (ex: Exception) {
            log.error("Kafka ping failed", ex)
            "kafka send failed: ${ex.message}"
        }

        return true
    }

}