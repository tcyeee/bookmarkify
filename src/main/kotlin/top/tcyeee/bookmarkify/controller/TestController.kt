package top.tcyeee.bookmarkify.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 测试类
 *
 * @author tcyeee
 * @date 3/9/25 19:53
 */
@RestController
@Tag(name = "测试相关")
@RequestMapping("/test")
class TestController {

    @Operation(
        summary = "连接测试",
        parameters = [Parameter(name = "type", description = "测试类型"), Parameter(
            name = "params",
            description = "测试参数"
        )]
    )
    @GetMapping("/link")
    fun linkTest(type: Int?, params: Array<String>?): Boolean {
        return true
    }
}