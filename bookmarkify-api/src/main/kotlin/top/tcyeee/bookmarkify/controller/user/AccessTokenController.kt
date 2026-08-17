package top.tcyeee.bookmarkify.controller.user

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.AccessTokenCreateParams
import top.tcyeee.bookmarkify.entity.AccessTokenCreatedVO
import top.tcyeee.bookmarkify.entity.AccessTokenVO
import top.tcyeee.bookmarkify.entity.enums.UserBehaviorType
import top.tcyeee.bookmarkify.server.IAccessTokenService
import top.tcyeee.bookmarkify.server.IUserBehaviorLogService
import top.tcyeee.bookmarkify.utils.BaseUtils

/**
 * 浏览器插件访问令牌管理：走正常登录会话(satoken)，与插件实际调用的 /extension 接口鉴权方式不同。
 * 详见根目录 ACCESS_TOKEN_DESIGN.md。
 *
 * @author tcyeee
 */
@RestController
@Tag(name = "插件访问令牌")
@RequestMapping("/user/access-token")
class AccessTokenController(
    private val accessTokenService: IAccessTokenService,
    private val userBehaviorLogService: IUserBehaviorLogService,
) {

    @PostMapping("/create")
    @Operation(summary = "生成一个新的访问令牌，响应含明文 token(仅此一次)")
    fun create(@RequestBody params: AccessTokenCreateParams): AccessTokenCreatedVO {
        val uid = BaseUtils.uid()
        val result = accessTokenService.create(params, uid)
        userBehaviorLogService.record(uid, UserBehaviorType.CREATE_ACCESS_TOKEN, result.name)
        return result
    }

    @GetMapping("/list")
    @Operation(summary = "查看自己名下的全部访问令牌(不含明文)")
    fun list(): List<AccessTokenVO> = accessTokenService.listMine(BaseUtils.uid())

    @PostMapping("/revoke")
    @Operation(summary = "撤销一个访问令牌")
    fun revoke(@RequestParam id: String): Boolean = accessTokenService.revoke(id, BaseUtils.uid())
}
