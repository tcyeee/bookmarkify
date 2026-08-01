package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.core.metadata.IPage
import top.tcyeee.bookmarkify.entity.OssObjectSearchParams
import top.tcyeee.bookmarkify.entity.OssObjectVO
import top.tcyeee.bookmarkify.entity.entity.OssObjectEntity
import top.tcyeee.bookmarkify.entity.enums.OssAddressing
import top.tcyeee.bookmarkify.entity.enums.OssObjectSource

/**
 * OSS 对象账本。
 *
 * 当前阶段（`FILE-SYSTEM-REFACTOR.md` 的 P1）这是一本**旁路账本**：写入路径双写，读路径完全
 * 不读它。所以这个接口刻意只有"记账"和"查账"，没有任何删除入口 —— 删对象的决定权在对账任务
 * （P2）手里，业务代码只负责报告"我往桶里写了什么"。
 */
interface IOssObjectService {

    /**
     * 记一笔。已存在的 key 原样跳过，**同一个 key 永远对应同一行**。
     *
     * 幂等是硬要求，不是优化：P3 把 `site_asset` 切到 `file_id` 之后，
     * `SiteAssetWriter.isIdenticalToExisting` 要靠"同 key ⇒ 同 id"才能继续认出"站点没改版"
     * 并跳过整体替换。每次抓取都新发一个 id 会让那个优化静默失效。
     *
     * **绝不抛异常。** 账本写失败不该影响任何业务流程 —— 漏记的对象由 P2 的对账任务从桶里
     * 反向补齐，而一次记账失败拖垮一条抓取或一次头像上传是不可接受的。
     *
     * @param objectKey 完整 object key（不含域名）。空白直接忽略
     * @return 入账后的行；失败或入参无效时返回 null
     */
    fun register(
        objectKey: String?,
        source: OssObjectSource,
        addressing: OssAddressing,
        contentHash: String? = null,
        size: Long? = null,
        mime: String? = null,
        width: Int? = null,
        height: Int? = null,
        isVector: Boolean = false,
    ): OssObjectEntity?

    /**
     * 批量记账，语义同 [register]，单条失败不影响其余。
     *
     * @return object key → 账本行ID。调用方据此把 `file_id` 盖到自己的行上
     */
    fun registerAll(specs: Collection<OssObjectSpec>): Map<String, String>

    fun findByKey(objectKey: String): OssObjectEntity?

    fun findById(id: String?): OssObjectEntity?

    /**
     * 标记对象已从桶中删除。
     *
     * **不删行**：账本留着这一行才能回答"这个 key 曾经存在、是什么时候没的"。行删了之后，
     * 对账任务再看到桶里残留的同名对象只会把它当成全新的孤儿，历史就断了。
     */
    fun markDeleted(id: String?)

    /** 按一批 object key 取回账本行，key → 行。P3 的读路径靠它一次取完，避免 N+1 */
    fun findByKeys(objectKeys: Collection<String>): Map<String, OssObjectEntity>

    /** 按一批文件ID取回账本行，id → 行。同样是批量入口 */
    fun findByIds(ids: Collection<String>): Map<String, OssObjectEntity>

    fun adminListAll(params: OssObjectSearchParams): IPage<OssObjectVO>
}

/** [IOssObjectService.registerAll] 的入参，字段含义与 [IOssObjectService.register] 一一对应 */
data class OssObjectSpec(
    val objectKey: String?,
    val source: OssObjectSource,
    val addressing: OssAddressing,
    val contentHash: String? = null,
    val size: Long? = null,
    val mime: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val isVector: Boolean = false,
)
