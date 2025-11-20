package top.tcyeee.bookmarkify.config.entity.sms

import cn.hutool.json.JSONUtil

/**
 * @author tcyeee
 * @date 4/15/25 17:59
 */
data class SmsParams(
    val code: Int? = 0
) {
    fun json(): String = JSONUtil.toJsonStr(this)
}
