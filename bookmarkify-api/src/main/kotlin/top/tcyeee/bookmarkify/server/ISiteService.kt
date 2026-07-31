package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.entity.SiteEntity

interface ISiteService : IService<SiteEntity> {

    /**
     * 按 host 获取或创建站点。并发插入同一 host 时，落败的一方捕获唯一键冲突后回查已存在记录，
     * 保证「一域名一行」。
     */
    fun getOrCreateByHost(host: String, scheme: String): SiteEntity

    fun findByHost(host: String): SiteEntity?

    /** 批量取回（避免 N+1）。返回 siteId -> 站点。 */
    fun mapByIds(siteIds: Collection<String>): Map<String, SiteEntity>

    /**
     * 把一次抓取拿到的站点级文字信息写回。
     *
     * [fromRootPage] 决定写入强度，这是本方法存在的全部理由：
     * - `true`（抓的是站点首页）→ 权威来源，覆盖现有值；
     * - `false`（抓的是深链）→ 二等来源，**只在现有值为空时回填**。
     *
     * 否则某个视频页里写歪的 `og:site_name` 会把整站品牌名带跑。
     * `verifyFlag` 与 `locked_fields` 一律尊重：人工确认过的值任何抓取都不覆盖。
     */
    fun applyCrawledMeta(siteId: String, brandName: String?, shortName: String?, fromRootPage: Boolean)

    /**
     * 写入 NSFW 判定结果。
     *
     * **判定结果为 false 也要写**（写进 `nsfw_reason`），否则"没判过"和"判过且干净"无法区分，
     * 每一次重抓都会重新烧一次 10s 的 LLM 往返 —— 而结论对整个域名从来不会变。
     */
    fun markNsfw(siteId: String, nsfw: Boolean, reason: String?)

    /**
     * 记录一次域名级活性结论。
     *
     * 只由巡检调用，且判死那一侧**必须**先由根地址探测确认过 —— 详见
     * `BookmarkServiceImpl.updateSiteLiveness`：一个被删的视频不该把整个域名标记成死亡，
     * 那会让该域名下所有页面被短路成失联而不再被实际探测，误判再没有机会纠正。
     */
    fun recordLiveness(siteId: String, alive: Boolean)
}
