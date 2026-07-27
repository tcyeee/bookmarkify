package top.tcyeee.bookmarkify.server.impl

import cn.hutool.core.util.HexUtil
import cn.hutool.crypto.SecureUtil
import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.AccessTokenCreateParams
import top.tcyeee.bookmarkify.entity.AccessTokenCreatedVO
import top.tcyeee.bookmarkify.entity.AccessTokenVO
import top.tcyeee.bookmarkify.entity.entity.AccessTokenEntity
import top.tcyeee.bookmarkify.mapper.AccessTokenMapper
import top.tcyeee.bookmarkify.server.IAccessTokenService
import java.security.SecureRandom
import java.time.LocalDateTime

/**
 * @author tcyeee
 */
@Service
class AccessTokenServiceImpl : IAccessTokenService, ServiceImpl<AccessTokenMapper, AccessTokenEntity>() {

    private val secureRandom = SecureRandom()

    override fun create(params: AccessTokenCreateParams, uid: String): AccessTokenCreatedVO {
        val secretBytes = ByteArray(24).also { secureRandom.nextBytes(it) }
        val rawToken = TOKEN_PREFIX + HexUtil.encodeHexStr(secretBytes)
        val entity = AccessTokenEntity(
            uid = uid,
            name = params.name.trim().takeIf { it.isNotBlank() } ?: DEFAULT_NAME,
            // 只存哈希；明文仅在本次返回给用户，之后任何地方(含数据库、日志)都读不到
            tokenHash = SecureUtil.sha256(rawToken),
            tokenPrefix = displayPrefix(rawToken),
        )
        save(entity)
        return AccessTokenCreatedVO(id = entity.id, name = entity.name, token = rawToken, createTime = entity.createTime)
    }

    override fun listMine(uid: String): List<AccessTokenVO> =
        list(KtQueryWrapper(AccessTokenEntity::class.java).eq(AccessTokenEntity::uid, uid).orderByDesc(AccessTokenEntity::createTime))
            .map { AccessTokenVO(it) }

    override fun revoke(id: String, uid: String): Boolean {
        val entity = getById(id) ?: throw CommonException(ErrorType.E124)
        if (entity.uid != uid) throw CommonException(ErrorType.E124)
        return removeById(id)
    }

    override fun verify(rawToken: String): String? {
        if (!rawToken.startsWith(TOKEN_PREFIX)) return null
        val hash = SecureUtil.sha256(rawToken)
        val entity = getOne(KtQueryWrapper(AccessTokenEntity::class.java).eq(AccessTokenEntity::tokenHash, hash)) ?: return null
        // lastUsedAt 仅用于展示，更新失败不应影响本次鉴权结果
        runCatching {
            ktUpdate().eq(AccessTokenEntity::id, entity.id).set(AccessTokenEntity::lastUsedAt, LocalDateTime.now()).update()
        }.onFailure { log.warn("[AccessToken] 更新 lastUsedAt 失败: ${it.message}") }
        return entity.uid
    }

    /** 展示用前缀：暴露 token 前段一部分供用户在列表中辨认，其余部分(占大部分熵)不落库、不展示 */
    private fun displayPrefix(rawToken: String): String {
        val visible = rawToken.take(TOKEN_PREFIX.length + 8)
        return "$visible****"
    }

    companion object {
        private const val TOKEN_PREFIX = "bmk_ext_"
        private const val DEFAULT_NAME = "未命名令牌"
    }
}
