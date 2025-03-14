package top.tcyeee.bookmarkify.entity.response

import top.tcyeee.bookmarkify.entity.dto.UserInfo
import top.tcyeee.bookmarkify.entity.po.UserEntity

/**
 * 登录返回的用户个人信息
 *
 * @author tcyeee
 * @date 3/11/25 21:58
 */
data class UserAuthEntityVo(
    var uid: String,
    val token: String,
    val nickName: String? = null,
    val mail: String? = null,
) {
    constructor(user: UserInfo, token: String) : this(
        uid = user.uid, token = token, nickName = user.nickName, mail = user.email
    )

    constructor(user: UserEntity, token: String) : this(
        uid = user.uid, token = token, nickName = user.nickName, mail = user.email
    )
}