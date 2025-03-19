package top.tcyeee.bookmarkify.entity.response

import cn.hutool.core.bean.BeanUtil
import top.tcyeee.bookmarkify.entity.po.UserEntity

data class UserInfoShow(
    var uid: String,
    var nickName: String,
    var phone: String? = null,
    var email: String? = null,
    var avatarPath: String? = null,
) {
    constructor(user: UserEntity) : this("", "") {
        BeanUtil.copyProperties(user, this)
    }
}
