package top.tcyeee.bookmarkify.controller.share

import cn.dev33.satoken.annotation.SaIgnore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.ShareCreateParams
import top.tcyeee.bookmarkify.entity.SharePublicVO
import top.tcyeee.bookmarkify.entity.UserShareVO
import top.tcyeee.bookmarkify.server.IUserShareService
import top.tcyeee.bookmarkify.utils.BaseUtils

/**
 * 书签分享
 * @author tcyeee
 */
@RestController
@Tag(name = "书签分享")
@RequestMapping("/share")
class ShareController(
    private val userShareService: IUserShareService,
) {

    @PostMapping("/create")
    @Operation(summary = "创建并发布分享")
    fun create(@RequestBody params: ShareCreateParams): UserShareVO =
        userShareService.createShare(params, BaseUtils.uid())

    @SaIgnore
    @GetMapping("/view")
    @Operation(summary = "查看分享内容(公开,无需登录)")
    fun view(@RequestParam code: String): SharePublicVO = userShareService.viewByCode(code)
}
