package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.kotlin.KtUpdateWrapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.OssObjectSearchParams
import top.tcyeee.bookmarkify.entity.OssObjectVO
import top.tcyeee.bookmarkify.entity.entity.OssObjectEntity
import top.tcyeee.bookmarkify.entity.enums.OssAddressing
import top.tcyeee.bookmarkify.entity.enums.OssObjectSource
import top.tcyeee.bookmarkify.entity.enums.OssObjectState
import top.tcyeee.bookmarkify.mapper.OssObjectMapper
import top.tcyeee.bookmarkify.server.IOssObjectService
import top.tcyeee.bookmarkify.server.OssObjectSpec

/**
 * OSS 对象账本实现。
 *
 * 全部写入都包在 [runCatching] 里：这本账现在还没有任何读者（P1 是旁路阶段），让它把一次头像
 * 上传或一条抓取搞失败是纯粹的负收益。参照 `ai_call_log` 的处理方式 —— 表不存在时只产生 warn，
 * 业务不受影响。
 */
@Service
class OssObjectServiceImpl(
    private val ossObjectMapper: OssObjectMapper,
) : IOssObjectService {

    /**
     * `REQUIRES_NEW`：记账必须与调用方的事务**完全隔离**。
     *
     * 抓取落库（`SiteAssetWriter.persist`）是一个大事务，把记账挂在它里面有两个方向的风险：
     * 抓取回滚会连带撤销已经真实发生的桶写入记录（对象是 scrapper 在事务之外 PUT 的，回滚不掉），
     * 而记账万一失败又会污染抓取事务的状态。开一个独立事务，两边互不影响。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = [Exception::class])
    override fun register(
        objectKey: String?,
        source: OssObjectSource,
        addressing: OssAddressing,
        contentHash: String?,
        size: Long?,
        mime: String?,
        width: Int?,
        height: Int?,
        isVector: Boolean,
    ): OssObjectEntity? {
        val key = normalizeKey(objectKey) ?: return null
        return runCatching {
            ossObjectMapper.insertIgnore(
                OssObjectEntity(
                    objectKey = key,
                    contentHash = contentHash?.trim()?.takeIf { it.isNotEmpty() },
                    addressing = addressing,
                    source = source,
                    size = size,
                    mime = mime?.trim()?.takeIf { it.isNotEmpty() },
                    width = width,
                    height = height,
                    isVector = isVector,
                )
            )
            // insertIgnore 命中冲突时返回 0 且不回填 id，统一按 key 回查拿到真正在库的那一行
            findByKeyInternal(key)
        }.onFailure {
            log.warn("[OssObject] 入账失败(忽略): key={}, source={}, err={}", key, source, it.message)
        }.getOrNull()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = [Exception::class])
    override fun registerAll(specs: Collection<OssObjectSpec>): Map<String, String> {
        val keys = mutableListOf<String>()
        specs.forEach { spec ->
            val key = normalizeKey(spec.objectKey) ?: return@forEach
            keys += key
            runCatching {
                ossObjectMapper.insertIgnore(
                    OssObjectEntity(
                        objectKey = key,
                        contentHash = spec.contentHash?.trim()?.takeIf { it.isNotEmpty() },
                        addressing = spec.addressing,
                        source = spec.source,
                        size = spec.size,
                        mime = spec.mime?.trim()?.takeIf { it.isNotEmpty() },
                        width = spec.width,
                        height = spec.height,
                        isVector = spec.isVector,
                    )
                )
            }.onFailure {
                log.warn("[OssObject] 入账失败(忽略): key={}, source={}, err={}", key, spec.source, it.message)
            }
        }
        // 一次回查拿全部 id：insertIgnore 命中冲突时不回填 id，且本来就有一部分行是早先入账的
        return findByKeys(keys).mapValues { (_, row) -> row.id }
    }

    override fun findByKey(objectKey: String): OssObjectEntity? =
        normalizeKey(objectKey)?.let { key -> runCatching { findByKeyInternal(key) }.getOrNull() }

    override fun findByKeys(objectKeys: Collection<String>): Map<String, OssObjectEntity> {
        val keys = objectKeys.mapNotNull { normalizeKey(it) }.distinct()
        if (keys.isEmpty()) return emptyMap()
        return runCatching {
            ossObjectMapper.selectList(
                KtQueryWrapper(OssObjectEntity::class.java).`in`(OssObjectEntity::objectKey, keys)
            ).associateBy { it.objectKey }
        }.getOrDefault(emptyMap())
    }

    override fun findByIds(ids: Collection<String>): Map<String, OssObjectEntity> {
        val fileIds = ids.filter { it.isNotBlank() }.distinct()
        if (fileIds.isEmpty()) return emptyMap()
        return runCatching {
            ossObjectMapper.selectList(
                KtQueryWrapper(OssObjectEntity::class.java).`in`(OssObjectEntity::id, fileIds)
            ).associateBy { it.id }
        }.getOrDefault(emptyMap())
    }

    override fun findById(id: String?): OssObjectEntity? {
        val fileId = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { ossObjectMapper.selectById(fileId) }.getOrNull()
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = [Exception::class])
    override fun markDeleted(id: String?) {
        val fileId = id?.trim()?.takeIf { it.isNotEmpty() } ?: return
        runCatching {
            ossObjectMapper.update(
                null,
                KtUpdateWrapper(OssObjectEntity::class.java)
                    .eq(OssObjectEntity::id, fileId)
                    .set(OssObjectEntity::state, OssObjectState.DELETED)
            )
        }.onFailure { log.warn("[OssObject] 标记删除失败(忽略): id={}, err={}", fileId, it.message) }
    }

    override fun adminListAll(params: OssObjectSearchParams): IPage<OssObjectVO> =
        ossObjectMapper.selectPage(params.toPage(), params.toWrapper()).convert { OssObjectVO(it) }

    private fun findByKeyInternal(key: String): OssObjectEntity? =
        ossObjectMapper.selectOne(KtQueryWrapper(OssObjectEntity::class.java).eq(OssObjectEntity::objectKey, key))

    /**
     * 只有**裸 key** 才进账本。
     *
     * 完整 URL 一律拒收：`site_asset.storage_url` 里存着改造前写入的整条 URL，其中一部分指向的
     * 根本不是我们的桶（资产只做了 PROBE 时存的是源站地址）。把外链记成"我方对象"会让 P2 的
     * 对账任务把它当孤儿清理 —— 那是删不掉也不该删的东西。存量 URL 的归并另有回填任务处理。
     */
    private fun normalizeKey(raw: String?): String? {
        val key = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (key.startsWith("http://", true) || key.startsWith("https://", true)) return null
        return key.removePrefix("/").substringBefore("?").takeIf { it.isNotEmpty() }
    }
}
