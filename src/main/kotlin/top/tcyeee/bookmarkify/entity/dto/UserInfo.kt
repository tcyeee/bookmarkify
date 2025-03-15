package top.tcyeee.bookmarkify.entity.dto

import cn.hutool.core.bean.BeanUtil
import top.tcyeee.bookmarkify.config.entity.RoleEnum
import top.tcyeee.bookmarkify.entity.po.UserEntity

/**
 * 存储在Session中的用户信息
 *
 * @author tcyeee
 * @date 3/14/25 19:35
 */
data class UserInfo(
    val uid: String,
    val nickName: String? = null,
    val email: String? = null,
    val phone: String? = null,
    var socketId: String? = null,
    val role: RoleEnum = RoleEnum.NONE,
) {
    constructor(user: UserEntity) : this(user.uid) {
        BeanUtil.copyProperties(user, this)
    }
}