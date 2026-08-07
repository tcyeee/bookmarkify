package top.tcyeee.bookmarkify.server.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import top.tcyeee.bookmarkify.entity.entity.ConfigChangeLogEntity
import top.tcyeee.bookmarkify.mapper.ConfigChangeLogMapper
import top.tcyeee.bookmarkify.mapper.UserMapper
import top.tcyeee.bookmarkify.server.ISystemConfigService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private const val KEY = "demo_config"

/** 两个字段之间有关系约束（a < b），正是逐项控件在前端表达不了、只能由服务端守住的那类不变量 */
private data class DemoConfig(val a: Int = 1, val b: Int = 10)

private class DemoAccessor(
    sc: ISystemConfigService,
    om: ObjectMapper,
    logMapper: ConfigChangeLogMapper,
    userMapper: UserMapper,
) : JsonConfigAccessor<DemoConfig>(KEY, DemoConfig::class.java, sc, om, logMapper, userMapper) {
    override fun fallback() = DemoConfig()
    override fun validate(value: DemoConfig) = require(value.a < value.b) { "a 必须小于 b" }
}

/**
 * 校验配置门面的三类行为，它们都**没有症状**，只能靠测试盯着：
 *
 * - 读路径的兜底：库里的值不可用时退回默认值，而不是让管理页 500、或让非法配置安静地生效；
 * - 缓存：取消「批量巡检必须把配置读一次再手工往下传」那条口头约定的前提；
 * - 变更留痕：写不进去只能影响审计，绝不能反过来让一次已经生效的保存报错。
 */
class JsonConfigAccessorTest {

    private val store = mutableMapOf<String, String>()
    private val objectMapper = jacksonObjectMapper()

    private val systemConfig: ISystemConfigService =
        Mockito.mock(ISystemConfigService::class.java).also { mock ->
            Mockito.`when`(mock.getValue(anyString()))
                .thenAnswer { store[it.getArgument<String>(0)] }
            Mockito.`when`(mock.setValue(anyString(), anyString()))
                .thenAnswer { store[it.getArgument<String>(0)] = it.getArgument(1); true }
        }

    private val changeLog: ConfigChangeLogMapper = Mockito.mock(ConfigChangeLogMapper::class.java)
    private val userMapper: UserMapper = Mockito.mock(UserMapper::class.java)

    private val accessor = DemoAccessor(systemConfig, objectMapper, changeLog, userMapper)

    @Test
    fun `表里没有这一行时返回兜底值`() {
        assertEquals(DemoConfig(), accessor.get())
    }

    @Test
    fun `JSON 读坏时退回兜底值而不是抛异常`() {
        // 配置读坏不该让整个管理页 500——管理员至少要能打开页面重新保存一次
        store[KEY] = "{ 这不是 JSON"
        assertEquals(DemoConfig(), accessor.get())
    }

    @Test
    fun `缺字段时由 data class 的默认值补齐`() {
        // 老数据写入时还没有 b 这一项；同一个机制也兜住了「API 先上线、旧后台提交缺字段」
        store[KEY] = """{"a":3}"""
        assertEquals(DemoConfig(a = 3, b = 10), accessor.get())
    }

    @Test
    fun `库中的值违反不变量时退回兜底值`() {
        // system_config 是一张可以被 psql 直接改的表。只在写路径校验的话，手工改坏的值会安静地
        // 生效，落到业务上是「某个状态永远不出现」这种查无可查的现象，而不是一个报错。
        store[KEY] = """{"a":99,"b":10}"""
        assertEquals(DemoConfig(), accessor.get())
    }

    @Test
    fun `保存前校验不通过就不写库也不动缓存`() {
        assertFailsWith<IllegalArgumentException> { accessor.update(DemoConfig(a = 99, b = 10)) }
        assertEquals(null, store[KEY])
        assertEquals(DemoConfig(), accessor.get())
    }

    @Test
    fun `重复读取只查一次库`() {
        repeat(5) { accessor.get() }
        Mockito.verify(systemConfig, Mockito.times(1)).getValue(KEY)
    }

    @Test
    fun `保存之后立即读到新值`() {
        accessor.get()
        accessor.update(DemoConfig(a = 2, b = 20))
        assertEquals(DemoConfig(a = 2, b = 20), accessor.get())
    }

    // ────── 变更留痕 ──────

    private fun captureLog(): ConfigChangeLogEntity {
        val captor = ArgumentCaptor.forClass(ConfigChangeLogEntity::class.java)
        Mockito.verify(changeLog).insert(captor.capture())
        return captor.value
    }

    @Test
    fun `首次写入时旧值为 null`() {
        accessor.update(DemoConfig(a = 2, b = 20))
        val row = captureLog()
        assertNull(row.oldValue, "库里本来就没有这一行，旧值只能是 null")
        assertEquals("""{"a":2,"b":20}""", row.newValue)
    }

    @Test
    fun `留痕记的是库里的原文而不是缓存里的兜底值`() {
        // 库中的值违反约束时 get() 返回兜底值，但审计要回答的是「这一行原本是什么」
        store[KEY] = """{"a":99,"b":10}"""
        assertEquals(DemoConfig(), accessor.get())
        accessor.update(DemoConfig(a = 2, b = 20))
        assertEquals("""{"a":99,"b":10}""", captureLog().oldValue)
    }

    @Test
    fun `校验不通过时不留痕`() {
        assertFailsWith<IllegalArgumentException> { accessor.update(DemoConfig(a = 99, b = 10)) }
        Mockito.verify(changeLog, Mockito.never()).insert(Mockito.any(ConfigChangeLogEntity::class.java))
    }

    @Test
    fun `留痕失败不影响配置保存`() {
        // 审计表晚于 API 上线（或建表迁移漏了）时，配置照常保存，只打一条 warn
        Mockito.`when`(changeLog.insert(Mockito.any(ConfigChangeLogEntity::class.java)))
            .thenThrow(RuntimeException("表不存在"))
        accessor.update(DemoConfig(a = 2, b = 20))
        assertEquals("""{"a":2,"b":20}""", store[KEY])
        assertEquals(DemoConfig(a = 2, b = 20), accessor.get())
    }
}
