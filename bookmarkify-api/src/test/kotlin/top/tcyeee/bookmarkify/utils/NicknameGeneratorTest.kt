package top.tcyeee.bookmarkify.utils

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NicknameGeneratorTest {

    @Test
    fun `random returns chinese words followed by 3 to 4 digits`() {
        val pattern = Regex("^[\\u4e00-\\u9fa5]+\\d{3,4}$")
        repeat(300) {
            val name = NicknameGenerator.random()
            assertTrue(name.isNotBlank()) { "昵称不能为空" }
            assertTrue(!name.startsWith("用户_")) { "不应再是旧默认值: $name" }
            assertTrue(pattern.matches(name)) { "格式不符: $name" }
        }
    }
}
