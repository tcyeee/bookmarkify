package top.tcyeee.bookmarkify.config.entity.sms

import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType

/**
 * @author tcyeee
 * @date 4/15/25 17:51
 */
enum class SmsType(val module: String) {
    CODE("SMS_121851388");  // 您的验证码为:${code},该验证码5分钟内有效,不要告诉别人哦

    companion object {
        fun decode(module: String): SmsType =
            SmsType.entries.find { it.module == module } ?: throw CommonException(ErrorType.E213)
    }
}