package top.tcyeee.bookmarkify.entity.po

import cn.hutool.core.util.IdUtil
import cn.hutool.core.util.RandomUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import top.tcyeee.bookmarkify.config.entity.RoleEnum
import top.tcyeee.bookmarkify.entity.request.LoginByClientForm
import java.time.LocalDateTime

/**
 * @author tcyeee
 * @date 1/12/24 23:26
 */
@TableName("sys_user")
data class UserEntity(
    @TableId var uid: String,
    @Max(200) @Schema(description = "昵称") var nickName: String,
    @Max(200) @Schema(description = "设备UID") var deviceUid: String,
    @Max(200) @Schema(description = "浏览器指纹") var fingerPrint: String,

    @Max(200) @Schema(description = "邮箱") var email: String? = null,
    @Max(20) @Schema(description = "手机号") var phone: String? = null,
    @JsonIgnore @Schema(description = "头像相对地址") var avatarPath: String? = null,
    @JsonIgnore @Max(200) @Schema(description = "用户密码MD5") var password: String? = null,

    @Schema(description = "用户角色 默认'NONE'") var role: RoleEnum = RoleEnum.NONE,
    @JsonIgnore @Schema(description = "创建时间") var updateTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @Schema(description = "是否已经被删除") var deleted: Boolean = false,
    @JsonIgnore @Schema(description = "是否禁用") var disabled: Boolean = false,
) {
    constructor(form: LoginByClientForm) : this(
        uid = IdUtil.fastUUID(),
        nickName = "用户_" + RandomUtil.randomString(5),
        deviceUid = form.deviceUid,
        fingerPrint = form.fingerprint,
    )

    // 判断设备信息是否变动
    fun checkDeviceInfo(form: LoginByClientForm): Boolean {
        return deviceUid != form.deviceUid || fingerPrint != form.fingerprint
    }
}