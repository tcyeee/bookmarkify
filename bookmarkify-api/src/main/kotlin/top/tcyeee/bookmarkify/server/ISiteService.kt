package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.SiteAdminVO
import top.tcyeee.bookmarkify.entity.SiteBasicInfoUpdateParams
import top.tcyeee.bookmarkify.entity.SiteSearchParams
import top.tcyeee.bookmarkify.entity.entity.SiteEntity

interface ISiteService : IService<SiteEntity> {

    /**
     * 管理后台的站点列表：一个域名一行。
     *
     * 与 `IBookmarkService.adminListAll` 是两个层，不是详略两版 —— 那边一行是一个页面，
     * 同域名下的 1000 个视频会把域名层的问题（品牌名没抓到、整站被判 NSFW、域名不可达）
     * 完全淹没。回填的页面数与图标都走批量查询，与行数无关。
     */
    fun adminListAll(params: SiteSearchParams): IPage<SiteAdminVO>

    /**
     * 单个站点的后台视图，回填内容与 [adminListAll] 的一行完全一致。
     *
     * 给站点/页面合并视图用：URL 上带着 `siteId` 直接进来时，那个站点未必落在左侧列表的
     * 当前分页里，摘要条不能只能靠列表命中。找不到返回 null（站点可能已被清理）。
     */
    fun adminDetail(siteId: String): SiteAdminVO?

    /**
     * 后台手工编辑站点信息，返回与 [adminDetail] 完全一致的新快照。
     *
     * 锁语义与 `BookmarkServiceImpl.adminUpdateBasicInfo` 一致，**手工编辑即加锁** ——
     * 否则下一轮定期重抓会在无人察觉的情况下把管理员刚填的品牌名改回抓取值。
     * 反过来，把某个名字显式清空（送空串）等于「我不要人工值了，交回抓取托管」，
     * 于是同时解锁；只想接受抓取值而不改现值时走 [SiteBasicInfoUpdateParams.unlockFields]。
     *
     * 返回完整 VO 而不是 void：调用方（站点/页面合并视图）拿它就地替换列表里那一行，
     * 省掉一次"保存完再整页重查"的往返，也避免列表与摘要条各拿一份快照而显示不一致。
     */
    fun adminUpdateBasicInfo(siteId: String, params: SiteBasicInfoUpdateParams): SiteAdminVO

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
