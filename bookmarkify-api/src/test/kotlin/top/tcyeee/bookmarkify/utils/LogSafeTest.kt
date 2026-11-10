package top.tcyeee.bookmarkify.utils

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 日志脱敏是一道**安全判据**，所以它有测试。
 *
 * 被修的那个 bug：`PreRequestFilter` 的限流告警把完整 satoken 打进了日志，而
 * `sa-token.timeout` 是 30 天 —— 滚动日志里那一串在整整一个月内都是一把能直接登录的钥匙。
 * 同一个仓库里 `GlobalExceptionHandler` 当时已经在正确地打码，两处行为不一致。
 */
@DisplayName("日志脱敏：既不能泄漏凭据，也要留下足以对照的痕迹")
class LogSafeTest {

    @Test
    @DisplayName("token 只留首尾各 4 位")
    fun `token keeps head and tail only`() {
        val token = "0199e4f1-7a2b-4c3d-8e5f-6a7b8c9d0e1f"
        val masked = LogSafe.maskToken(token)

        // 关键断言：原串不可从日志里还原
        assertThat(masked).doesNotContain(token)
        assertThat(masked).isEqualTo("0199…0e1f")
        // 保留首尾是为了让同一会话的多条日志还能彼此对照 —— 整条替换会让排查限流无从下手
        assertThat(masked).startsWith("0199").endsWith("0e1f")
    }

    @Test
    @DisplayName("过短的值整条打掉——那时首尾已经是全部")
    fun `short values are fully masked`() {
        assertThat(LogSafe.maskToken("abc")).isEqualTo("***")
        assertThat(LogSafe.maskToken("12345678")).isEqualTo("***")
        assertThat(LogSafe.maskToken(null)).isEqualTo("-")
        assertThat(LogSafe.maskToken("  ")).isEqualTo("-")
    }

    @Test
    @DisplayName("邮箱保留前 2 位与完整域名：够判断是不是同一个账号在被反复试")
    fun `email keeps prefix and domain`() {
        assertThat(LogSafe.maskEmail("tcyeee@outlook.com")).isEqualTo("tc***@outlook.com")
        // 前缀太短时不留前缀，否则 a@x.com 等于没打码
        assertThat(LogSafe.maskEmail("ab@x.com")).isEqualTo("***@x.com")
        assertThat(LogSafe.maskEmail("a@x.com")).isEqualTo("***@x.com")
    }

    @Test
    @DisplayName("不是邮箱的输入退化成 token 打码，而不是原样返回")
    fun `non email input falls back to token masking`() {
        // 归一化之前的脏输入（没有 @）绝不能一路原样打进日志
        assertThat(LogSafe.maskEmail("not-an-email-value")).isEqualTo("not-…alue")
        assertThat(LogSafe.maskEmail("@only-domain")).isEqualTo("@onl…main")
    }
}
