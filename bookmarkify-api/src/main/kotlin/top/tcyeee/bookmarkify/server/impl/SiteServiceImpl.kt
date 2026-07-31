package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.SiteEntity
import top.tcyeee.bookmarkify.entity.enums.SiteLockedField
import top.tcyeee.bookmarkify.mapper.SiteMapper
import top.tcyeee.bookmarkify.server.ISiteService
import java.time.LocalDateTime

@Service
class SiteServiceImpl : ISiteService, ServiceImpl<SiteMapper, SiteEntity>() {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun findByHost(host: String): SiteEntity? =
        ktQuery().eq(SiteEntity::host, host).one()

    /**
     * `site` 表在 `host` 上有唯一约束：并发添加同一域名下的两个页面时，落败的一方捕获冲突后回查。
     *
     * 与 `BookmarkServiceImpl.getOrCreateByUrl` 同样的注意事项：**调用方必须让它留在事务之外**。
     * 它靠捕获唯一键冲突后回查来收敛，而 PostgreSQL 里一旦事务内触发约束冲突，整个事务就进入
     * aborted 状态，回查那条 SELECT 也会一并失败。
     */
    override fun getOrCreateByHost(host: String, scheme: String): SiteEntity {
        findByHost(host)?.let { return it }
        return try {
            SiteEntity(host = host, scheme = scheme).also { save(it) }
                .also { log.debug("[getOrCreateByHost] 新建站点: siteId=${it.id}, host=$host, linkType=${it.linkType}") }
        } catch (e: DuplicateKeyException) {
            findByHost(host) ?: throw e
        }
    }

    override fun mapByIds(siteIds: Collection<String>): Map<String, SiteEntity> {
        val ids = siteIds.filter { it.isNotBlank() }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return baseMapper.selectByIds(ids).associateBy { it.id }
    }

    override fun applyCrawledMeta(siteId: String, brandName: String?, shortName: String?, fromRootPage: Boolean) {
        val site = baseMapper.selectById(siteId) ?: run {
            log.warn("[applyCrawledMeta] 站点不存在，跳过: siteId=$siteId")
            return
        }
        // 人工认证过的站点，品牌名与图标都已核对，任何抓取都不该覆盖
        if (site.verifyFlag) {
            log.debug("[applyCrawledMeta] 站点已人工认证，跳过写入: siteId=$siteId, host=${site.host}")
            return
        }

        val brand = pick(site.brandName, brandName, fromRootPage, site.isLocked(SiteLockedField.BRAND_NAME))
        val short = pick(site.shortName, shortName, fromRootPage, site.isLocked(SiteLockedField.SHORT_NAME))
        if (brand == null && short == null) return

        ktUpdate().eq(SiteEntity::id, siteId)
            .apply {
                brand?.let { set(SiteEntity::brandName, it) }
                short?.let { set(SiteEntity::shortName, it) }
            }
            .set(SiteEntity::updateTime, LocalDateTime.now())
            .update()
        log.debug("[applyCrawledMeta] 站点信息已更新: siteId=$siteId, host=${site.host}, brandName=$brand, shortName=$short, fromRootPage=$fromRootPage")
    }

    override fun recordLiveness(siteId: String, alive: Boolean) {
        val site = baseMapper.selectById(siteId) ?: return
        // 连续失败次数只在域名级失败时累加；恢复即归零。UNKNOWN 压根不会走到这里 ——
        // 调用方对"我方链路出问题"这种结论是直接跳过的，不能记在站点账上。
        val fail = if (alive) 0 else site.consecutiveFail + 1
        ktUpdate().eq(SiteEntity::id, siteId)
            .set(SiteEntity::isAlive, alive)
            .set(SiteEntity::lastCheckAt, LocalDateTime.now())
            .set(SiteEntity::consecutiveFail, fail)
            // 刻意不写 next_check_at：目前没有独立的站点级巡检调度器，域名活性是页面巡检的副产物。
            // 留 NULL 而不是填一个没人读的值，免得以后真加了调度器时被这批陈旧游标误导。
            .set(SiteEntity::updateTime, LocalDateTime.now())
            .update()
        logger.info("[recordLiveness] 站点活性更新: siteId=$siteId, host=${site.host}, alive=$alive, consecutiveFail=$fail")
    }

    override fun markNsfw(siteId: String, nsfw: Boolean, reason: String?) {
        // 判定为 false 也要落 reason：这一列同时承担「已经判过了」这个语义。
        // 只在命中时写（旧实现的做法）会让干净站点永远处于"没判过"状态，每次重抓都重判一遍。
        ktUpdate().eq(SiteEntity::id, siteId)
            .set(SiteEntity::nsfw, nsfw)
            .set(SiteEntity::nsfwReason, reason?.take(50) ?: if (nsfw) null else CLEAN_MARK)
            .set(SiteEntity::updateTime, LocalDateTime.now())
            .update()
        logger.debug("[markNsfw] 站点 NSFW 判定已落库: siteId=$siteId, nsfw=$nsfw, reason=$reason")
    }

    /**
     * 决定这次抓取该不该写这一列，返回要写入的值；`null` 表示不写。
     *
     * 三条规则叠加：
     * - 抓取值为空 → 不写。空值覆盖掉已有的好值是净损失。
     * - 该列被管理员锁定 → 不写。
     * - 抓的是首页 → 权威来源，覆盖；抓的是深链 → 只在现有值为空时回填。
     */
    private fun pick(current: String?, incoming: String?, fromRootPage: Boolean, locked: Boolean): String? {
        val value = incoming?.takeIf { it.isNotBlank() } ?: return null
        if (locked) return null
        if (fromRootPage) return value.takeIf { it != current }
        return if (current.isNullOrBlank()) value else null
    }

    companion object {
        /** 判过且干净时写入 `nsfw_reason` 的占位值，用来把"已判过"与"没判过"区分开。 */
        const val CLEAN_MARK = "CLEAN"
    }
}
