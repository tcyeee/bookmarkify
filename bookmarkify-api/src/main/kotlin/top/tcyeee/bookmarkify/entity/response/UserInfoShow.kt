package top.tcyeee.bookmarkify.entity.response

import cn.hutool.core.bean.BeanUtil
import cn.hutool.json.JSONUtil
import top.tcyeee.bookmarkify.entity.common.BackgroundConfig
import top.tcyeee.bookmarkify.entity.entity.UserEntity

data class UserInfoShow(
    var uid: String,
    var token: String,
    var nickName: String,
    var phone: String? = null,
    var email: String? = null,
    var avatarPath: String? = null,
    var backgroundPath: String? = null,
    var backgroundConfig: BackgroundConfig? = null,
    var verified: Boolean? = null,       // 是否为验证过的账户(例如绑定手机号,绑定邮箱等)
) {
    constructor(user: UserEntity,token: String) : this(
        uid = user.id,
        token = token,
        nickName = user.nickName,
    ) {
        BeanUtil.copyProperties(user, this)

        // 将存储在数据库中的 JSON 字符串转换为对象返回给前端
        user.backgroundConfigJson?.let {
            try {
                backgroundConfig = JSONUtil.toBean(it, BackgroundConfig::class.java)
            } catch (_: Exception) {
                // 解析失败时忽略，保持 null
            }
        }
    }
}
