package top.tcyeee.bookmarkify.utils

import cn.hutool.http.HttpUtil
import cn.hutool.json.JSONUtil
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.config.cache.RedisType
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.config.log

/**
 * 邮件工具类
 *
 * @author tcyeee
 */
@Component
class MailUtils {

    @Value("\${bookmarkify.wechat.corpid}")
    private lateinit var corpid: String

    @Value("\${bookmarkify.wechat.corpsecret}")
    private lateinit var corpsecret: String

    enum class EmailType(val title: String) {
        /* 验证码 */
        VERIFY_CODE("验证码"),
    }

    /**
     * 构建邮件正文(HTML)
     *
     * @param code 验证码(数字),大号加粗高亮,作为视觉焦点
     * @param ref  区分代码(字母),小号灰字,仅用于帮助用户识别本封邮件,不参与校验
     */
    private fun buildContent(type: EmailType, code: String, ref: String): String = when (type) {
        EmailType.VERIFY_CODE -> """
            <div style="font-family:-apple-system,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif;max-width:420px;margin:0 auto;padding:28px 24px;color:#1f2937;">
              <p style="margin:0 0 16px;font-size:15px;">您正在登录 <strong>书签鸭</strong>，本次验证码为：</p>
              <div style="margin:8px 0 20px;text-align:center;">
                <span style="display:inline-block;font-size:34px;font-weight:700;letter-spacing:10px;color:#4f46e5;background:#eef2ff;border-radius:12px;padding:14px 24px 14px 34px;">$code</span>
              </div>
              <p style="margin:0 0 6px;font-size:13px;color:#6b7280;">验证码 15 分钟内有效，请勿泄露给他人。</p>
              <p style="margin:14px 0 0;font-size:12px;color:#9ca3af;">本次请求标识：<span style="font-weight:600;">$ref</span>（仅用于识别本封邮件，无需填写）</p>
            </div>
        """.trimIndent()
    }

    /* 获取腾讯云邮TOKEN */
    private fun getAccessToken(): String {
        RedisUtils.getConst<String>(RedisType.WECHAT_WORK_ACCESS_TOKEN)?.let { return it }
        val url = "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=$corpid&corpsecret=$corpsecret"
        val response = HttpUtil.get(url)
        val json = JSONUtil.parseObj(response)
        if (json.getInt("errcode") != 0) throw CommonException(ErrorType.E217, json.getStr("errmsg"))
        return json.getStr("access_token")
            .also { RedisUtils.setConst(RedisType.WECHAT_WORK_ACCESS_TOKEN, it) }
    }

    fun send(to: String, type: EmailType, code: String, ref: String): Boolean {
        try {
            val accessToken = getAccessToken()
            val url = "https://qyapi.weixin.qq.com/cgi-bin/exmail/app/compose_send?access_token=$accessToken"

            val payload = mapOf(
                "to" to mapOf("emails" to listOf(to)),
                "subject" to type.title,
                "content_type" to "html",
                "content" to buildContent(type, code, ref)
            )

            val response = HttpUtil.post(url, JSONUtil.toJsonStr(payload))
            val json = JSONUtil.parseObj(response)

            if (json.getInt("errcode") == 0) {
                return true
            } else {
                val errMsg = json.getStr("errmsg")
                log.error("发送邮件失败: ${json.getStr("errmsg")}")
                if (errMsg.startsWith("not allow to access from your ip")) throw CommonException(ErrorType.E108)
                return false
            }
        } catch (e: Exception) {
            log.error("发送邮件异常", e)
            if (e is CommonException) throw e
            return false
        }
    }
}
