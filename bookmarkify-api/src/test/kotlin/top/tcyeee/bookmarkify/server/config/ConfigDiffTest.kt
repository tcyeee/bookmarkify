package top.tcyeee.bookmarkify.server.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 变更记录那一列的全部信息量都来自这里：一次保存提交的是整份配置，
 * 「这次动了哪一项」必须由比对算出来，算错了页面上看到的就是错的历史。
 */
class ConfigDiffTest {

    private val om = jacksonObjectMapper()

    private fun diff(old: String?, new: String) = ConfigDiff.of(old, new, om)

    @Test
    fun `只列出真正变了的字段`() {
        val changes = diff("""{"a":1,"b":2,"c":3}""", """{"a":1,"b":20,"c":3}""")
        assertEquals(1, changes.size, "只有 b 变了")
        assertEquals("b", changes[0].field)
        assertEquals("2", changes[0].oldValue)
        assertEquals("20", changes[0].newValue)
    }

    @Test
    fun `内容完全相同时没有变化`() {
        // 管理员打开页面什么都没改就点了保存，这一行不该显示成"改了七项"
        assertTrue(diff("""{"a":1,"b":2}""", """{"a":1,"b":2}""").isEmpty())
    }

    @Test
    fun `首次写入把每一项都列成变化`() {
        val changes = diff(null, """{"a":1,"b":2}""")
        assertEquals(2, changes.size)
        assertTrue(changes.all { it.oldValue == null }, "此前库里没有这一行，旧值只能是 null")
    }

    @Test
    fun `本次发版新增的配置项算一次变化`() {
        val changes = diff("""{"a":1}""", """{"a":1,"b":2}""")
        assertEquals(1, changes.size)
        assertEquals("b", changes[0].field)
        assertNull(changes[0].oldValue)
        assertEquals("2", changes[0].newValue)
    }

    @Test
    fun `被删掉的配置项同样算一次变化`() {
        // 顶层键取并集的理由：只看新值的键，字段被删这件事会从历史里消失
        val changes = diff("""{"a":1,"b":2}""", """{"a":1}""")
        assertEquals(1, changes.size)
        assertEquals("b", changes[0].field)
        assertEquals("2", changes[0].oldValue)
        assertNull(changes[0].newValue)
    }

    @Test
    fun `原文解析不了时返回空列表而不是抛异常`() {
        // 调用方仍然会把两份原文下发，读的人不至于因此什么都看不到
        assertTrue(diff("{ 这不是 JSON", """{"a":1}""").isEmpty())
    }
}
