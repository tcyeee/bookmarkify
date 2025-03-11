package top.tcyeee.bookmarkify.entity.response

import top.tcyeee.bookmarkify.entity.po.UserEntity

/**
 * @author tcyeee
 * @date 3/11/25 21:58
 */
data class UserEntityVo(
    val token: String,
    val nickName: String,
    val mail: String?
) {
    constructor(user: UserEntity, token: String) : this(
        token = token,
        nickName = user.nickName,
        mail = user.email
    )
}