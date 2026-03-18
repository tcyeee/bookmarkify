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

    enum class EmailType(val title: String, val content: String) {
        /* 验证码 */
        VERIFY_CODE("验证码", "您的验证码为: %s, 15分钟内有效"),
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

    fun send(to: String, type: EmailType, code: String): Boolean {
        try {
            val accessToken = getAccessToken()
            val url = "https://qyapi.weixin.qq.com/cgi-bin/exmail/app/compose_send?access_token=$accessToken"

            val payload = mapOf(
                "to" to mapOf("emails" to listOf(to)),
                "subject" to type.title,
                "content" to String.format(type.content, code)
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
