package top.tcyeee.bookmarkify.utils

import cn.hutool.crypto.digest.BCrypt
import java.security.MessageDigest

/**
 * 密码哈希工具。
 *
 * 历史遗留：旧数据库中 `user_info.password` 存放的是「客户端 MD5 后再 Base64 传输」得到的
 * 无盐 md5 十六进制串（服务端直接整列等值比较）。无盐 MD5 一旦库泄露即可被彩虹表秒破，
 * 且因为哈希发生在客户端，存储值本身就是可重放的凭据（pass-the-hash）。
 *
 * 现在统一以「md5 十六进制串」作为规范凭据（candidate），再用带盐的 BCrypt 落库。
 * 为兼容存量数据，[matches] 同时支持旧的明文 md5 行与新的 BCrypt 行；登录成功后由调用方
 * 通过 [needsUpgrade] 判断并把旧行重新哈希为 BCrypt（登录即升级）。
 *
 * @author tcyeee
 */
object PasswordUtils {

    /** BCrypt 哈希以 `$2a$` / `$2b$` / `$2y$` 开头 */
    fun isHashed(stored: String?): Boolean = stored != null && stored.startsWith("\$2")

    /** 用带盐 BCrypt 对规范凭据（md5 串）进行哈希 */
    fun encode(credential: String): String = BCrypt.hashpw(credential, BCrypt.gensalt())

    /**
     * 校验候选凭据是否匹配落库值。
     * - BCrypt 行：走 [BCrypt.checkpw]
     * - 旧明文 md5 行：常量时间比较，避免计时侧信道
     */
    fun matches(candidate: String, stored: String?): Boolean {
        if (stored.isNullOrBlank()) return false
        return if (isHashed(stored)) {
            runCatching { BCrypt.checkpw(candidate, stored) }.getOrDefault(false)
        } else {
            MessageDigest.isEqual(
                candidate.toByteArray(Charsets.UTF_8),
                stored.toByteArray(Charsets.UTF_8)
            )
        }
    }

    /** 落库值仍是旧的明文 md5（非 BCrypt）时，应在登录成功后升级为 BCrypt */
    fun needsUpgrade(stored: String?): Boolean = !isHashed(stored)
}
