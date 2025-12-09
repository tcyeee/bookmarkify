package top.tcyeee.bookmarkify.entity.dto

import cn.dev33.satoken.session.SaSession
import cn.dev33.satoken.stp.StpUtil
import cn.hutool.json.JSONUtil
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.entity.UserEntity

/**
 * 存储在Session中的用户信息
 *
 * @author tcyeee
 * @date 3/14/25 19:35
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class UserSessionInfo(
    @field:Schema(description = "UID") var uid: String,
    @field:Schema(description = "用户名称") var nickName: String,
    @field:Schema(description = "用户绑定的邮箱") var email: String? = null,
    @field:Schema(description = "用户绑定的手机号") var phone: String? = null,

    @field:Schema(description = "用户是否验证") var verified: Boolean? = false,
    @field:Schema(description = "用户TOKEN") var token: String,
) {
    constructor(user: UserEntity, token: String) : this(
        uid = user.id,
        nickName = user.nickName,
        email = user.email,
        phone = user.phone,
        token = token,
    ) {
        this.verified = this.phone != null || this.email != null
    }

    /* 写入STP的Sesssion */
    fun writeToSession(): UserSessionInfo = JSONUtil.createObj().apply {
        this["uid"] = uid
        this["nickName"] = nickName
        this["email"] = email
        this["phone"] = phone
        this["token"] = token
    }
        .let { JSONUtil.toJsonStr(it) }
        .also { StpUtil.getSession().set("user", it) }
        .let { this }

    /* 从STP-Session中读取 */
    constructor(session: SaSession) : this(
        uid = "",
        nickName = "",
        email = "",
        phone = "",
        token = ""
    ) {
        val json = session.get("user").toString()
        val res = JSONUtil.parseObj(json)

        this.uid = res["uid"].toString()
        this.nickName = res["nickName"].toString()
        this.email = res["email"].toString()
        this.phone = res["phone"].toString()
        this.token = res["token"].toString()

        this.verified = this.phone != null || this.email != null
    }
}