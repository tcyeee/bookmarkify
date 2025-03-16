package top.tcyeee.bookmarkify.entity.dto

import cn.hutool.json.JSONUtil
import top.tcyeee.bookmarkify.entity.po.UserEntity

/**
 * 存储在Session中的用户信息
 *
 * @author tcyeee
 * @date 3/14/25 19:35
 */
data class UserInfo(
    var uid: String? = null,
    var nickName: String? = null,
    var email: String? = null,
    var phone: String? = null,
    var socketId: String? = null,
) {
    constructor(user: UserEntity) : this() {
        this.uid = user.uid
        this.nickName = user.nickName
        this.email = user.email
        this.phone = user.phone
    }

    constructor(json: String) : this() {
        val res = JSONUtil.parseObj(json)
        this.uid = res["uid"].toString()
        this.nickName = res["nickName"].toString()
        this.email = res["email"].toString()
        this.phone = res["phone"].toString()
        this.socketId = res["socketId"].toString()
    }

    fun json(): String {
        val res = JSONUtil.createObj()
        res["uid"] = uid
        res["nickName"] = nickName
        res["email"] = email
        res["phone"] = phone
        res["socketId"] = socketId
        return JSONUtil.toJsonStr(res)
    }
}