package top.tcyeee.bookmarkify.controller

import cn.dev33.satoken.annotation.SaIgnore
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.server.IBookmarkService


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
    private val ibookmarkService: IBookmarkService
) {

    @SaIgnore
    @GetMapping("/link")
    fun linkTest(type: Int, param: String?): Boolean {
        val bookmark = ibookmarkService.getById(param)
        ibookmarkService.checkOne(bookmark)
        return true
    }
}