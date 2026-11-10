package top.tcyeee.bookmarkify.server.parse

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.mapper.PageMapper
import top.tcyeee.bookmarkify.server.asset.SiteAssetWriter

/**
 * 把**解析链路里的未预期异常**落成一条可查询的失败快照。
 *
 * ## 为什么需要它：账本只记"我方打出去的调用"，不记"我方自己的落库"
 *
 * 2026-11-09/10 生产上的那次故障是这个盲区的标准形状：抓取成功了（`scrapper_call_log` 这一行
 * 写的是 `success = true`），随后 `UPDATE page` 因为 `title` 超过 `varchar(200)` 抛
 * `DataIntegrityViolationException`，于是：
 *
 * - `page.parse_err_msg` 是空的 —— 那次 update 整条失败了，错误信息也没写进去；
 * - `scrapper_call_log` 说这次调用成功；
 * - `scrape_snapshot` 里没有失败行 —— 抓取确实没失败；
 * - 唯一的痕迹是事件监听器那行 `log.error` 带的栈，躺在一个 30 天后就会被删掉的文本文件里
 *   （当天 156 行、前一天 52 行）。
 *
 * 也就是说：**用户可见的症状（标题不更新/磁贴一直是旧的）在结构化数据里一个字节都没有**，
 * 事后连"这件事从哪天开始的、影响了多少页面"都答不出来。补上这条记录之后，同类故障在后台
 * 的抓取快照里就是一条写明原因的失败行，按页面可查、可计数。
 *
 * ## 为什么复用 `scrape_snapshot` 而不是再建一张表
 *
 * 那张表的语义正是"这个页面某一次抓取尝试的结果，成或败"（`persistFailure` 早就在写失败行），
 * 而这里记的就是同一次尝试的另一种败法 —— 败在最后一步落库上。再建一张"落库失败表"只会让
 * 同一个问题分裂成两处去查，而排查时的问题从来是"这个页面到底怎么了"，不是"它败在第几步"。
 */
@Component
class ParseFailureRecorder(
    private val pageMapper: PageMapper,
    private val siteAssetWriter: SiteAssetWriter,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param pageId 没有它就写不了（`scrape_snapshot.page_id` 非空），直接跳过 ——
     *   导入链路的事件只带 rawUrl，那时页面还可能根本没建出来，编一个归属比不记更糟。
     */
    fun record(pageId: String?, error: Throwable) {
        if (pageId == null) return
        runCatching {
            val url = pageMapper.selectById(pageId)?.rawUrl ?: return@runCatching
            siteAssetWriter.persistFailure(
                pageId = pageId,
                url = url,
                errorMsg = "解析链路未预期异常: ${error.javaClass.simpleName}: ${error.message}".take(1000),
                durationMs = 0,
            )
        }.onFailure {
            // 记录失败现场本身失败了，只能退回文本日志 —— 但绝不能让它盖掉原始异常
            log.warn("[ParseFailureRecorder] 失败快照落库失败(忽略): pageId={}, err={}", pageId, it.message)
        }
    }
}
