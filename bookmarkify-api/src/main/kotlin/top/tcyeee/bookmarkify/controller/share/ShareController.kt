package top.tcyeee.bookmarkify.controller.share

import cn.dev33.satoken.annotation.SaIgnore
import com.baomidou.mybatisplus.core.metadata.IPage
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.config.result.PageBean
import top.tcyeee.bookmarkify.entity.ShareCreateParams
import top.tcyeee.bookmarkify.entity.SharePublicVO
import top.tcyeee.bookmarkify.entity.ShareUpdateParams
import top.tcyeee.bookmarkify.entity.UserShareVO
import top.tcyeee.bookmarkify.entity.enums.UserBehaviorType
import top.tcyeee.bookmarkify.server.IUserBehaviorLogService
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
    private val userBehaviorLogService: IUserBehaviorLogService,
) {

    @PostMapping("/create")
    @Operation(summary = "创建并发布分享")
    fun create(@RequestBody params: ShareCreateParams): UserShareVO {
        val uid = BaseUtils.uid()
        val result = userShareService.createShare(params, uid)
        userBehaviorLogService.record(
            uid,
            UserBehaviorType.PUBLISH_SHARE,
            "包含 ${result.bookmarkCount} 条书签" + (result.note?.let { " · $it" } ?: ""),
        )
        return result
    }

    @SaIgnore
    @GetMapping("/view")
    @Operation(summary = "查看分享内容(公开,无需登录)")
    fun view(@RequestParam code: String): SharePublicVO = userShareService.viewByCode(code)

    @PostMapping("/mine")
    @Operation(summary = "分页查看我发布的全部分享")
    fun mine(@RequestBody params: PageBean): IPage<UserShareVO> = userShareService.listMine(BaseUtils.uid(), params)

    @PostMapping("/cancel")
    @Operation(summary = "下架自己发布的分享")
    fun cancel(@RequestParam id: String): Boolean = userShareService.cancelShare(id, BaseUtils.uid())

    @PostMapping("/update")
    @Operation(summary = "修改自己发布的分享(文案/过期时间)")
    fun update(@RequestBody params: ShareUpdateParams): UserShareVO = userShareService.updateShare(params, BaseUtils.uid())
}
