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
    var uid: String,
    var nickName: String? = null,
    var email: String? = null,
    var phone: String? = null,
    var socketId: String? = null,
) {
    constructor(user: UserEntity) : this(user.uid) {
        this.uid = user.uid
        this.nickName = user.nickName
        this.email = user.email
        this.phone = user.phone
    }

    constructor(json: String) : this(uid = "") {
        println("=======================")
        println(json)
        println("=======================")
        val parseObj = JSONUtil.parseObj(json)
        this.uid = parseObj["uid"].toString()
        this.nickName = parseObj["nickName"].toString()
        this.email = parseObj["email"].toString()
        this.phone = parseObj["phone"].toString()
        this.socketId = parseObj["socketId"].toString()
    }

    fun json(): String {
        val result = JSONUtil.createObj()
        result["uid"] = uid
        result["nickName"] = nickName
        result["email"] = email
        result["phone"] = phone
        result["socketId"] = socketId
        return JSONUtil.toJsonStr(result)
    }
}