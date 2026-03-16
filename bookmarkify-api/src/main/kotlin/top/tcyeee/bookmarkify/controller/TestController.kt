package top.tcyeee.bookmarkify.controller

import cn.dev33.satoken.annotation.SaIgnore
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.server.IApiService
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
    private val bookmarkService: IBookmarkService,
    private val iapiService: IApiService
) {

    @SaIgnore
    @GetMapping("/link")
    fun linkTest(type: Int, param: String): Boolean {
        if (type == 1) {
            val bookmark = bookmarkService.getById(param)
            bookmarkService.parseBookmarkByApi(bookmark)
            return true
        }
        if (type == 2) {
            iapiService.queryWebsiteInfo(param)
            return true
        }
        if (type == 3) {
            iapiService.queryWebsiteInfo(param)
            return true
        }
        return true
    }
}