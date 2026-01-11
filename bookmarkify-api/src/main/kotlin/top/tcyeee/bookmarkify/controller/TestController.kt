package top.tcyeee.bookmarkify.controller

import cn.dev33.satoken.annotation.SaIgnore
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
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
    private val messageService: IKafkaMessageService
) {

    @SaIgnore
    @GetMapping("/link")
    fun linkTest(type: Int): Boolean {
        if (type == 1) return this.kafkaTest()
        return true
    }

    private fun kafkaTest(): Boolean {
        messageService.linkTest("Hello World")
        return true
    }
}