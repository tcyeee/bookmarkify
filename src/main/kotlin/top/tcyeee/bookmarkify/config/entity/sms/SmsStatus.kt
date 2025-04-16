package top.tcyeee.bookmarkify.config.entity.sms

import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType

/**
 * @author tcyeee
 * @date 4/15/25 19:02
 */
enum class SmsStatus(var code: Long) {
    LOADING(1),  // 等待回执
    ERROR(2),    // 发送失败
    SUCCESS(3);  // 发送成功

    companion object {
        fun decode(code: Long): SmsStatus = entries.find { it.code == code } ?: throw CommonException(ErrorType.E212)
    }
}