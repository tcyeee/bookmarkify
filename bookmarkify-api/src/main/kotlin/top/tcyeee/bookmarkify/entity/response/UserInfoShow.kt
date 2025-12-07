package top.tcyeee.bookmarkify.entity.response

import cn.hutool.core.bean.BeanUtil
import top.tcyeee.bookmarkify.entity.entity.UserEntity

data class UserInfoShow(
    var uid: String,
    var token: String,
    var nickName: String,
    var phone: String? = null,
    var email: String? = null,
    var avatarFileId: String? = null,
    var verified: Boolean? = null,       // 是否为验证过的账户(例如绑定手机号,绑定邮箱等)
) {
    constructor(user: UserEntity, token: String) : this(
        uid = user.id,
        token = token,
        nickName = user.nickName,
    ) {
        BeanUtil.copyProperties(user, this)
    }
}
