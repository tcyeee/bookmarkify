package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import cn.hutool.core.util.RandomUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size
import top.tcyeee.bookmarkify.config.entity.RoleEnum
import top.tcyeee.bookmarkify.entity.UserInfoShow
import top.tcyeee.bookmarkify.entity.dto.UserSessionInfo
import java.time.LocalDateTime

/**
 * @author tcyeee
 * @date 1/12/24 23:26
 */
@TableName("sys_user")
data class UserEntity(
    @TableId("id") var id: String,
    @field:Size(max = 200) @field:Schema(description = "昵称") var nickName: String,
    @field:Size(max = 200) @field:Schema(description = "设备UID") var deviceId: String,

    @field:Size(max = 200) @field:Schema(description = "邮箱") var email: String? = null,
    @field:Size(max = 20) @field:Schema(description = "手机号") var phone: String? = null,
    @field:Size(max = 200) @field:Schema(description = "用户密码MD5") var password: String? = null,
    @field:Schema(description = "头像文件地址") var avatarFileId: String? = null,
    @field:Schema(description = "用户角色 默认'NONE'") var role: RoleEnum = RoleEnum.NONE,
    @field:Schema(description = "创建时间") var updateTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "是否已经被删除") var deleted: Boolean = false,
    @field:Schema(description = "是否禁用") var disabled: Boolean = false,
    @field:Schema(description = "是否有过任何形式的验证") var verified: Boolean = false,
) {
    constructor(deviceId: String) : this(
        id = IdUtil.fastUUID(),
        nickName = "用户_" + RandomUtil.randomString(5),
        deviceId = deviceId,
    )

    fun authVO(): UserSessionInfo = UserSessionInfo(this)

    fun vo(): UserInfoShow = UserInfoShow(this)
}