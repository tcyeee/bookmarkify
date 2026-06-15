package top.tcyeee.bookmarkify.utils

import cn.hutool.crypto.SecureUtil
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 校验密码哈希与「登录即升级」迁移逻辑。
 */
class PasswordUtilsTest {

    private val credential = SecureUtil.md5("hunter2") // 规范凭据：md5 串

    @Test
    fun `bcrypt roundtrip matches`() {
        val hashed = PasswordUtils.encode(credential)
        assertTrue(PasswordUtils.isHashed(hashed))
        assertTrue(PasswordUtils.matches(credential, hashed))
        assertFalse(PasswordUtils.matches("wrong", hashed))
    }

    @Test
    fun `two encodes of same credential differ (salted) but both verify`() {
        val a = PasswordUtils.encode(credential)
        val b = PasswordUtils.encode(credential)
        assertTrue(a != b, "BCrypt 应带随机盐，两次哈希结果不同")
        assertTrue(PasswordUtils.matches(credential, a))
        assertTrue(PasswordUtils.matches(credential, b))
    }

    @Test
    fun `legacy plaintext md5 row still verifies and is flagged for upgrade`() {
        // 旧数据：password 列直接存 md5 串
        val legacy = credential
        assertFalse(PasswordUtils.isHashed(legacy))
        assertTrue(PasswordUtils.matches(credential, legacy))
        assertFalse(PasswordUtils.matches("nope", legacy))
        assertTrue(PasswordUtils.needsUpgrade(legacy), "旧明文 md5 行应被标记升级")
    }

    @Test
    fun `bcrypt row does not need upgrade`() {
        val hashed = PasswordUtils.encode(credential)
        assertFalse(PasswordUtils.needsUpgrade(hashed))
    }

    @Test
    fun `null or blank stored never matches`() {
        assertFalse(PasswordUtils.matches(credential, null))
        assertFalse(PasswordUtils.matches(credential, ""))
        assertFalse(PasswordUtils.matches(credential, "   "))
    }
}
