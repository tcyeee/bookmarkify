package top.tcyeee.bookmarkify.entity.response

import top.tcyeee.bookmarkify.config.entity.RoleEnum
import top.tcyeee.bookmarkify.entity.po.UserEntity

/**
 * @see UserEntity
 */
data class UserEntityVo(
    var uid: String,
    var nickName: String,
    var email: String? = null,
    var phone: String? = null,
    var avatarPath: String? = null,
    var role: RoleEnum = RoleEnum.NONE,
)
