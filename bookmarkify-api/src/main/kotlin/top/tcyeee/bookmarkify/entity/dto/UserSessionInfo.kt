package top.tcyeee.bookmarkify.entity.dto

import cn.dev33.satoken.session.SaSession
import cn.hutool.json.JSONUtil
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.entity.UserEntity
import top.tcyeee.bookmarkify.utils.StpKit

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
    @field:Schema(description = "已关联的 Google 邮箱(未关联为 null)") var googleEmail: String? = null,

    @field:Schema(description = "用户是否验证") var verified: Boolean = false,
    @field:Schema(description = "用户TOKEN") var token: String,
) {
    constructor(user: UserEntity, token: String) : this(
        uid = user.id,
        nickName = user.nickName,
        email = user.email,
        googleEmail = user.googleEmail,
        token = token,
    ) {
        this.verified = this.email != null
    }

    /* 写入STP的Session */
    fun writeToSession(): UserSessionInfo = JSONUtil.createObj().apply {
        this.set("uid", uid)
        this.set("nickName", nickName)
        this.set("token", token)
        this.set("verified", verified)
        if (email != null) this.set("email", email)
    }
        .let { JSONUtil.toJsonStr(it) }
        .also { StpKit.USER.session.set(SaSession.USER, it) }
        .let { this }

    /* 从STP-Session中读取 */
    constructor(obj: Any) : this(uid = "", nickName = "", token = "") {
        val res = JSONUtil.parseObj(obj.toString())
        this.uid = res.getStr("uid")
        this.nickName = res.getStr("nickName")
        this.token = res.getStr("token")

        if (res.containsKey("email")) this.email = res.getStr("email")
        this.verified = res.containsKey("email")
    }
}