package top.tcyeee.bookmarkify.server.admin

import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.entity.UserAdminVO
import top.tcyeee.bookmarkify.entity.entity.UserInfoEntity
import top.tcyeee.bookmarkify.mapper.AccessTokenMapper
import top.tcyeee.bookmarkify.mapper.BookmarkMapper
import top.tcyeee.bookmarkify.mapper.UserMapper
import top.tcyeee.bookmarkify.server.IOssObjectService

/**
 * 后台用户视图的组装器：实体 → [UserAdminVO]，并把头像签好。
 *
 * 单独成一个组件而不是挂在 `IUserService` 上，是因为书签后台列表也要用它（列表上要显示收录者
 * 的头像+昵称）。而 `UserServiceImpl` 依赖 `IBookmarkService`，反过来注入就成了 Spring 默认
 * 拒绝的循环依赖。这里只依赖 mapper 与账本，两边都能安全注入。
 */
@Component
class AdminUserViewAssembler(
    private val userMapper: UserMapper,
    private val bookmarkMapper: BookmarkMapper,
    private val accessTokenMapper: AccessTokenMapper,
    private val ossObjectService: IOssObjectService,
) {

    /**
     * 一批用户的头像签名地址，uid → URL。没有头像或账本里查不到的用户直接缺席。
     *
     * 头像存的是 oss_object 账本ID，私有桶的裸 key 浏览器用不了，必须服务端签。按 file_id
     * 去重后一次 in 查询：逐行签就是一页一次 N+1。
     */
    fun avatarUrls(users: Collection<UserInfoEntity>): Map<String, String> {
        val signed = ossObjectService
            .findByIds(users.mapNotNull { it.avatarFileId?.takeIf(String::isNotBlank) }.distinct())
            .mapNotNull { (id, obj) -> obj.signedUrl(ADMIN_AVATAR_SIZE)?.let { id to it } }
            .toMap()
        return users.mapNotNull { user -> user.avatarFileId?.let(signed::get)?.let { user.id to it } }.toMap()
    }

    /** 一批用户实体 → VO（顺序与入参一致），头像批量签名 */
    fun toVOs(users: Collection<UserInfoEntity>): List<UserAdminVO> {
        if (users.isEmpty()) return emptyList()
        val avatars = avatarUrls(users)
        val ids = users.map { it.id }.distinct()
        val bookmarkCounts = bookmarkMapper.countActiveByUids(ids).associate { it.uid to it.count }
        val tokenCounts = accessTokenMapper.countByUids(ids).associate { it.uid to it.count }
        return users.map { user ->
            UserAdminVO(user).apply {
                avatarUrl = avatars[user.id]
                bookmarkCount = bookmarkCounts[user.id] ?: 0
                tokenCount = tokenCounts[user.id] ?: 0
            }
        }
    }

    /** 按用户ID批量取回后台视图，uid → VO。查不到的ID直接缺席 */
    fun findByIds(uids: Collection<String>): Map<String, UserAdminVO> {
        val ids = uids.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val users = userMapper.selectList(KtQueryWrapper(UserInfoEntity::class.java).`in`(UserInfoEntity::id, ids))
        return toVOs(users).associateBy { it.id }
    }

    companion object {
        // 后台头像格子最大 64px，2x 屏取 128 足够，回原图只是白烧带宽
        const val ADMIN_AVATAR_SIZE = 128
    }
}
