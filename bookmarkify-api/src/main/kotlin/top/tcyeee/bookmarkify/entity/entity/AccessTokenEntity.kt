package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/**
 * 浏览器插件访问令牌：用户在设置页自行生成，供插件调用 /extension 路径下的只读接口。
 * 与 Sa-Token 的登录会话完全独立，见根目录 ACCESS_TOKEN_DESIGN.md。
 *
 * @author tcyeee
 */
@TableName("access_token")
data class AccessTokenEntity(
    @TableId val id: String = IdUtil.fastUUID(),
    @field:Schema(description = "所属用户ID") var uid: String,
    @field:Schema(description = "用户自定义备注") var name: String,
    // 只存哈希，明文只在创建那一刻返回给用户一次，之后任何地方都读不到
    @field:Schema(description = "token 的 SHA-256 哈希") var tokenHash: String,
    @field:Schema(description = "展示用前缀，如 bmk_ext_a1b2****") var tokenPrefix: String,
    @field:Schema(description = "最近一次校验通过时间") var lastUsedAt: LocalDateTime? = null,
    @field:Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
)
