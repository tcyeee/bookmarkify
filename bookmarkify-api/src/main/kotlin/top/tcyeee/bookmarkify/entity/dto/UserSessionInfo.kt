package top.tcyeee.bookmarkify.entity.dto

import cn.dev33.satoken.session.SaSession
import cn.hutool.json.JSONUtil
import top.tcyeee.bookmarkify.entity.entity.UserEntity

/**
 * 存储在Session中的用户信息
 *
 * @author tcyeee
 * @date 3/14/25 19:35
 */
data class UserSessionInfo(
    var uid: String,
    var nickName: String,
    var email: String? = null,
    var phone: String? = null,

    var socketId: String? = null,
    var token: String? = null,
) {
    constructor(user: UserEntity) : this(
        uid = user.id,
        nickName = user.nickName,
        email = user.email,
        phone = user.phone,
    )

    /* 写入STP的Sesssion */
    fun writeToSession(): UserSessionInfo = JSONUtil.createObj().apply {
        this["uid"] = uid
        this["nickName"] = nickName
        this["email"] = email
        this["phone"] = phone
        this["socketId"] = socketId
    }.let { JSONUtil.toJsonStr(it) }.let { this }

    /* 从STP-Session中读取 */
    constructor(session: SaSession) : this(
        uid = "",
        nickName = "",
        email = "",
        phone = "",
        socketId = "",
        token = ""
    ) {
        val json = session.get("user").toString()
        val res = JSONUtil.parseObj(json)

        this.uid = res["uid"].toString()
        this.nickName = res["nickName"].toString()
        this.email = res["email"].toString()
        this.phone = res["phone"].toString()
        this.socketId = res["socketId"].toString()
        this.token = res["token"].toString()
    }
}