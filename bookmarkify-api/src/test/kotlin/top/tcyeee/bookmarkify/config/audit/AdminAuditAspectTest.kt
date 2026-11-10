package top.tcyeee.bookmarkify.config.audit

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockingDetails
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory
import org.springframework.web.bind.annotation.PathVariable
import top.tcyeee.bookmarkify.entity.entity.AdminActionLogEntity
import top.tcyeee.bookmarkify.mapper.UserMapper
import top.tcyeee.bookmarkify.server.IAdminActionLogService

/**
 * 后台审计切面：**失败的操作也必须留痕，而且异常要照原样抛出去。**
 *
 * 这个切面是 `admin_action_log` 唯一的写入方，而它的两条关键行为都是"不做也不会报错"的类型：
 * 漏记一条审计没有任何症状；把异常吞掉则会让管理员以为删除成功了。所以两条都钉在这里。
 *
 * 用 `AspectJProxyFactory` 直接织入一个桩对象，不起 Spring 上下文 —— 这个仓库没有
 * Spring 上下文测试（见 CLAUDE.md 的测试形状说明），为一个切面引一整套上下文不划算，
 * 而要验的东西（记了什么、异常有没有透传）与容器无关。
 */
@DisplayName("后台审计切面：记下谁做了什么，成败都记，且不改变调用结果")
class AdminAuditAspectTest {

    /** 被织入的桩：形状刻意照搬真实后台接口（路径变量 + 带凭据的请求体） */
    open class FakeAdminController {
        @AdminAudit("删除页面")
        open fun deletePage(@PathVariable pageId: String): String = "deleted:$pageId"

        @AdminAudit("后台登录")
        open fun login(params: FakeLoginParams): String = "ok"

        @AdminAudit("修改站点信息")
        open fun boom(@PathVariable siteId: String): String = throw IllegalStateException("db down")
    }

    data class FakeLoginParams(val account: String, val password: String)

    private val logService = mock(IAdminActionLogService::class.java)
    private val userMapper = mock(UserMapper::class.java)

    private fun proxy(): FakeAdminController {
        val factory = AspectJProxyFactory(FakeAdminController())
        factory.addAspect(AdminAuditAspect(logService, userMapper, ObjectMapper()))
        factory.isProxyTargetClass = true
        return factory.getProxy()
    }

    /**
     * 取出切面写下的那条审计。
     *
     * 走 `mockingDetails(...).invocations` 而不是 `ArgumentCaptor`：Kotlin 的非空形参遇上
     * `captor.capture()`（它返回 null）会直接 NPE / InvalidUseOfMatchers —— 这是 Kotlin + Mockito
     * 的经典坑，与被测逻辑无关，没必要为它引一个 mockito-kotlin 依赖。
     */
    private fun captured(): AdminActionLogEntity {
        val invocation = mockingDetails(logService).invocations.first { it.method.name == "record" }
        return invocation.getArgument(0)
    }

    @Test
    @DisplayName("成功的操作：记下动作名、代码位置、操作对象，并原样返回结果")
    fun `successful action is recorded`() {
        val result = proxy().deletePage("page-42")

        assertThat(result).isEqualTo("deleted:page-42")
        val entity = captured()
        assertThat(entity.action).isEqualTo("删除页面")
        assertThat(entity.handler).endsWith(".deletePage")
        // 操作对象取第一个 @PathVariable —— 审计里"对哪条记录做的"全靠它
        assertThat(entity.targetId).isEqualTo("page-42")
        assertThat(entity.success).isTrue()
        assertThat(entity.errorMsg).isNull()
    }

    @Test
    @DisplayName("失败的操作同样记一行，并且异常必须继续往上抛")
    fun `failed action is recorded and the exception propagates`() {
        // 吞掉异常会让管理员以为操作成功了；而只记成功的审计表分不出
        // 「试过但没成功」和「没试过」—— 两件完全不同的事
        assertThatThrownBy { proxy().boom("site-7") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("db down")

        val entity = captured()
        assertThat(entity.success).isFalse()
        assertThat(entity.errorMsg).contains("IllegalStateException").contains("db down")
        assertThat(entity.targetId).isEqualTo("site-7")
    }

    @Test
    @DisplayName("请求体里的密码不会进审计表")
    fun `credentials never reach the audit row`() {
        proxy().login(FakeLoginParams("tcyeee@outlook.com", "super-secret"))

        val entity = captured()
        // 这张表保留两年：凭据漏进去比没有审计更糟。判据本体见 AuditParamRedactorTest
        assertThat(entity.params).isNotNull()
        assertThat(entity.params!!).doesNotContain("super-secret")
        assertThat(entity.params!!).contains("tcyeee@outlook.com")
    }

    @Test
    @DisplayName("没有登录态时 operatorId 为空，而不是让切面自己炸掉")
    fun `missing admin session leaves the operator empty`() {
        // 这个测试里压根没有 Sa-Token 上下文 —— 真实场景是后台登录失败那一刻（会话还没建立），
        // 那正是最该留痕的一行，所以取不到操作者绝不能让记录整条写不进去
        proxy().deletePage("page-1")

        assertThat(captured().operatorId).isNull()
    }
}
