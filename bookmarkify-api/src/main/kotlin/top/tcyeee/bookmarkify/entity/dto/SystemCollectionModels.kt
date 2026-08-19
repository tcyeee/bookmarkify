package top.tcyeee.bookmarkify.entity.dto

import top.tcyeee.bookmarkify.entity.enums.SystemCollectionStatus
import java.time.LocalDateTime

/** 系统书签集发布流程使用的书签摘要，与 bookmarkify-admin `systemCollection.ts` 的同名接口逐字段对应。 */
data class CollectionBookmark(
    val pageId: String,
    val rawUrl: String,
    val title: String? = null,
    val description: String? = null,
    val iconUrl: String? = null,
)

data class ExtractCollectionLinksParams(val text: String = "")

/** AI 从长文本中提取到的原始链接（已去重、已补全协议前缀），可能包含误判的非链接文本，交由抓取阶段自然过滤 */
data class ExtractCollectionLinksResult(val links: List<String> = emptyList())

data class StartCollectionCrawlParams(val urls: List<String> = emptyList())

data class StartCollectionCrawlResult(val jobId: String, val count: Int)

/** 系统书签集批量抓取的逐条进度，经 WebSocket(SYSTEM_COLLECTION_CRAWL_UPDATE) 推送给发起的管理员 */
data class CollectionCrawlUpdate(
    val jobId: String,
    val rawUrl: String,
    /** SUCCESS | FAILED（终态才推送，正在抓取的中间态由前端本地推断，见 admin 端注释） */
    val status: String,
    val bookmark: CollectionBookmark? = null,
    val errorMsg: String? = null,
)

data class GenerateCollectionMetaParams(val bookmarks: List<CollectionBookmark> = emptyList())

data class CollectionMetaResult(val title: String, val description: String)

data class PublishSystemCollectionParams(
    val title: String = "",
    val description: String = "",
    val pageIds: List<String> = emptyList(),
)

/** 系统书签集的后台视图：列表与详情共用，`bookmarks` 按发布/编辑时的 `pageIds` 顺序排列 */
data class SystemCollectionVO(
    val id: String,
    val title: String,
    val description: String,
    val status: SystemCollectionStatus,
    val bookmarks: List<CollectionBookmark>,
    val createTime: LocalDateTime,
    val updateTime: LocalDateTime,
)
