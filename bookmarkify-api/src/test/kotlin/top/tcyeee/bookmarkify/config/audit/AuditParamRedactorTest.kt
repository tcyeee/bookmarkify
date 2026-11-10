package top.tcyeee.bookmarkify.config.audit

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 审计摘要**绝不能**把凭据带进 `admin_action_log`。
 *
 * 这张表保留两年，而后台登录接口的入参就是 `(account, password)` —— 不打码的话，一个记录
 * "谁动了什么"的机制会变成一处凭据泄漏点，比没有审计更糟。所以这条判据有测试。
 */
@DisplayName("后台审计入参摘要：记下做了什么，但不记凭据")
class AuditParamRedactorTest {

    private val mapper = ObjectMapper()

    private data class LoginParams(val account: String, val password: String)
    private data class NestedParams(val name: String, val inner: LoginParams)
    private data class OAuthParams(val idToken: String, val redirect: String)

    @Test
    @DisplayName("密码字段被替换成 ***，其余字段照常保留")
    fun `password is masked`() {
        val json = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
            LoginParams("tcyeee@outlook.com", "s3cr3t-pwd")
        )
        val redacted = AuditParamRedactor.redact(json).toString()

        assertThat(redacted).doesNotContain("s3cr3t-pwd")
        assertThat(redacted).contains("***")
        // 账号留着：审计要回答"谁试图登录"
        assertThat(redacted).contains("tcyeee@outlook.com")
    }

    @Test
    @DisplayName("嵌套对象里的凭据同样被打掉")
    fun `nested credentials are masked`() {
        val json = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
            NestedParams("x", LoginParams("a@b.com", "deep-secret"))
        )
        assertThat(AuditParamRedactor.redact(json).toString()).doesNotContain("deep-secret")
    }

    @Test
    @DisplayName("判据按字段名包含匹配，覆盖 idToken/secret/pwd 这类变体")
    fun `sensitive match is by substring`() {
        assertThat(AuditParamRedactor.isSensitive("password")).isTrue()
        assertThat(AuditParamRedactor.isSensitive("pwd")).isTrue()
        assertThat(AuditParamRedactor.isSensitive("idToken")).isTrue()
        assertThat(AuditParamRedactor.isSensitive("clientSecret")).isTrue()
        // 这些是审计真正要看的东西，不能被顺手打掉
        assertThat(AuditParamRedactor.isSensitive("pageId")).isFalse()
        assertThat(AuditParamRedactor.isSensitive("action")).isFalse()
    }

    @Test
    @DisplayName("参数名本身敏感时，整个值被打掉——哪怕它是个裸字符串")
    fun `top level sensitive parameter is masked`() {
        val summary = AuditParamRedactor.summarize(
            names = listOf("name", "level", "password"),
            values = listOf("top.tcyeee", "DEBUG", "raw-password"),
            mapper = mapper,
        )
        assertThat(summary).isNotNull()
        assertThat(summary!!).doesNotContain("raw-password")
        assertThat(summary).contains("top.tcyeee").contains("DEBUG")
    }

    @Test
    @DisplayName("OAuth 入参的 idToken 不进库")
    fun `oauth token is masked`() {
        val summary = AuditParamRedactor.summarize(
            names = listOf("params"),
            values = listOf(OAuthParams("eyJhbGciOi.very.long", "/home")),
            mapper = mapper,
        )
        assertThat(summary!!).doesNotContain("eyJhbGciOi.very.long").contains("/home")
    }

    @Test
    @DisplayName("没有可记录的参数时返回 null，而不是一个空对象")
    fun `empty payload is null`() {
        // "{}" 在审计列表里是纯噪音：它既不说明做了什么，又占满一列
        assertThat(AuditParamRedactor.summarize(emptyList(), emptyList(), mapper)).isNull()
    }
}
