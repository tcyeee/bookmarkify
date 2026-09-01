package top.tcyeee.bookmarkify.config.event

/**
 * 「更多相似书签」冷计算事件：对某个站点烧一次 DeepSeek 推荐、再逐个收录返回的域名。
 *
 * 单独走事件而不是在 `similarFor` 里直接调 `@Async` 方法：`@Async` 对同类自调用不生效，
 * 直接调会让用户那次 `POST /bookmark/similar?pageId=` 同步阻塞几十秒到几分钟。
 * 由 [SimilarColdComputeListener] 在 `bookmarkEnrichExecutor` 上消费（没人等结果，慢一点无所谓）。
 *
 * @param siteId 属主站点
 * @param title / description 站点首页的标题与简介，喂给 DeepSeek（可能为空，此时只靠 host 推）
 * @param host  站点域名
 */
data class SimilarColdComputeEvent(
    val siteId: String,
    val title: String?,
    val description: String?,
    val host: String,
)
