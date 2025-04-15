package top.tcyeee.bookmarkify.entity.response

import cn.hutool.core.bean.BeanUtil
import top.tcyeee.bookmarkify.entity.entity.UserEntity

data class UserInfoShow(
    var uid: String,
    var nickName: String,
    var phone: String? = null,
    var email: String? = null,
    var avatarPath: String? = null,
    var verified: Boolean? = null,       // 是否为验证过的账户(例如绑定手机号,绑定邮箱等)
) {
    constructor(user: UserEntity) : this(
        uid = user.uid,
        nickName = user.nickName,
    ) {
        BeanUtil.copyProperties(user, this)
    }
}
