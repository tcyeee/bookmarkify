package top.tcyeee.bookmarkify.utils

/**
 * 写进日志之前必须过一道的脱敏。
 *
 * ## 为什么要有这么个对象
 *
 * `GlobalExceptionHandler` 早就在给 token 打码（只留首尾各 4 位），而 `PreRequestFilter`
 * 的限流告警把**完整的 satoken** 原样打进了日志 —— 同一个仓库里，同一个值，两种做法。
 * 那不是风格问题：`sa-token.timeout` 是 2592000 秒（30 天），也就是说日志文件里那一串
 * 在整整一个月内都是一把能直接登录的钥匙，而滚动日志保留 30 天、docker json 日志另有一份。
 *
 * 把判断收到一个地方，才谈得上"所有打 token 的地方都打了码"。
 */
object LogSafe {

    /**
     * 凭据脱敏：只留首尾各 4 位。
     *
     * 保留首尾而不是整条替换，是为了日志之间还能对得上 —— 同一个会话的两条记录看得出是同一个
     * token，而 8 个字符远不足以还原一个 uuid。太短的值一律整条打掉（那时首尾已经是全部）。
     */
    fun maskToken(token: String?): String = when {
        token.isNullOrBlank() -> "-"
        token.length <= 8 -> "***"
        else -> "${token.take(4)}…${token.takeLast(4)}"
    }

    /**
     * 邮箱脱敏：保留首 2 位与完整域名（`ab***@gmail.com`）。
     *
     * 登录失败日志要回答的是"是不是同一个账号在被反复试"，域名与前缀足够做这个判断；
     * 而完整邮箱是用户的个人信息，不该为了排查一次密码错误就落在磁盘上 30 天。
     */
    fun maskEmail(email: String?): String {
        if (email.isNullOrBlank()) return "-"
        val at = email.indexOf('@')
        if (at <= 0) return maskToken(email)
        val name = email.take(at)
        val domain = email.substring(at)
        return if (name.length <= 2) "***$domain" else "${name.take(2)}***$domain"
    }
}
