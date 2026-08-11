package top.tcyeee.bookmarkify.server.parse

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.entity.PageEntity
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.server.IBookmarkLivenessConfigService
import top.tcyeee.bookmarkify.server.asset.SiteAssetWriter
import top.tcyeee.bookmarkify.server.liveness.LivenessPolicy
import top.tcyeee.bookmarkify.server.liveness.PageScheduleWriter
import java.time.LocalDateTime

/**
 * 一次抓取的**两种终态**，以及"这次失败该不该算在站点头上"的判据。
 *
 * ## 为什么是一个共享组件
 *
 * `parse_status` / `is_activity` / `parse_err_msg` / 调度列这四组字段之间是有约束的
 * （SUCCESS 必然 `isActivity = true` 且 `parseErrMsg` 为空），而写它们的地方有两拨：
 *
 * - **自动抓取链路**（`BookmarkServiceImpl` 的 `parseByApi` / `parseLocally` / 事件监听器）
 * - **后台的人工操作**（`BookmarkAdminService` 的检测活性 / 一键更新 / 重抓资产 / 外部同步）
 *
 * 这五行此前被逐字复制了十遍，收口成两个私有扩展函数之后又只在一个类里可见 —— 后台那批方法
 * 拆出去时，唯一的选择要么是把它们再抄一遍，要么是把这段实现提上来。抄一遍的代价不是重复，
 * 是**不一致时没有症状**：漏掉 `scheduleAfterParse*` 那一句不会报任何错，那条记录的
 * `next_check_at` 就停在旧值上，要么被每轮巡检重复选中，要么再也不被选中。
 *
 * 与 [PageScheduleWriter] 是同一个理由的两层：那个管调度列，这个管终态字段并调用它。
 *
 * **新增解析路径时不要再手写这四个字段，调这里的两个方法。**
 */
@Component
class PageParseStateWriter(
    private val livenessConfigService: IBookmarkLivenessConfigService,
    private val scheduleWriter: PageScheduleWriter,
    private val siteAssetWriter: SiteAssetWriter,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 落成「抓到了」：可用、SUCCESS、清空错误、推进内容刷新周期。
     *
     * 注意它**不写库** —— 调用方往往还要在同一次 update 里带上 title/资产等其它字段，
     * 强行在这里落库会变成两次写。
     */
    fun markSucceeded(page: PageEntity): PageEntity = page.apply {
        isActivity = true
        parseStatus = ParseStatusEnum.SUCCESS
        parseErrMsg = null
        updateTime = LocalDateTime.now()
        scheduleWriter.advanceAfterParseSuccess(this)
    }

    /**
     * 落成「这个站点抓不到」：记下原因、计入连续失败走退避，**够次数了才**真的判失活。
     *
     * ⚠️ 只用于**目标站点**的失败。「我方抓取服务不可用」(E307) 绝不能走到这里——那种情况必须
     * 保持 PENDING 不收口，否则用户桌面上那个节点会脱离 BOOKMARK_LOADING、也就脱离
     * `drainStuckLoading` 的重投递范围，之后即使抓取成功也没有任何机制会把结果回传给它。
     * 判据见 [isScrapperUnavailable]，每个调用点都在进来之前先挡了一道。
     *
     * ## 为什么这里也要过判失活门槛
     *
     * 「抓取失败」同样是一次检查不通过，而其中一条路径完全绕开了巡检那侧的门槛：
     * `livenessCheckStaleBookmarks` ping 通了、发现内容过期(30 天)、投递重新抓取，
     * 而这一次抓取只要失败（超时、被反爬拒一次），书签立刻变灰 —— 一个**活得好好的**站点，
     * 被负责保护它的那条巡检亲手判死，且只用了一次失败。
     *
     * 判据取「这条记录原本是不是 SUCCESS」，不需要把调用场景一路透传下来：
     * - 原本 SUCCESS：它此前抓成功过，这次失败更可能是偶发，攒够 `deadConfirmFailures` 次
     *   才收口。在此之前保留原有的标题与图标——用户看到的是旧内容，而不是一个灰掉的磁贴。
     * - 原本 PENDING（新添加、导入）：从未成功过，必须就地收口给出终态，
     *   否则用户桌面上那个 LOADING 节点没有着落。这也是判失活门槛唯一的例外。
     */
    fun markUnreachable(page: PageEntity, errMsg: String?): PageEntity = page.apply {
        // 之前抓成功过的记录，这一次失败不足以推翻它
        val provenGood = parseStatus == ParseStatusEnum.SUCCESS
        val config = livenessConfigService.getConfig()
        updateTime = LocalDateTime.now()
        // 先推进调度：consecutiveFail 在这里 +1，下面的门槛判定要用推进之后的值
        scheduleWriter.advanceAfterParseFailure(this)
        if (!provenGood || LivenessPolicy.confirmsDead(consecutiveFail, config.deadConfirmFailures)) {
            isActivity = false
            parseStatus = ParseStatusEnum.UNREACHABLE
            // 刻意只在真正判失活时才写错误原因：SUCCESS 必然伴随空 parseErrMsg 是这几个
            // 字段之间的约束，留一条错误信息在一条 SUCCESS 记录上，后台看到的就是
            //「可用的书签却挂着报错」。失败现场另有去处 —— recordScrapeFailure 落了完整快照，
            // consecutive_fail 记了次数
            parseErrMsg = errMsg
        } else log.warn(
            "[markUnreachable] 抓取失败但此前可用(第 $consecutiveFail 次，" +
                "达 ${config.deadConfirmFailures} 次方判失活)，保留原有内容: " +
                "pageId=$id, urlHost=$urlHost, err=$errMsg"
        )
    }

    /**
     * 把一次失败的抓取现场落成快照。
     *
     * 与 [markUnreachable] 分开调用：**没判失活的失败同样要留现场**。判失活有门槛
     * （连续 N 次），而每一次失败的原因都可能不同，只在收口那一刻记一条会丢掉前面几次的证据。
     *
     * 失败只记 warn：这是观测数据，写不进去不该反过来影响业务结果。
     */
    fun recordScrapeFailure(page: PageEntity, e: Throwable, startedAt: Long) {
        runCatching {
            siteAssetWriter.persistFailure(page.id, page.rawUrl, e.message, elapsedMs(startedAt))
        }.onFailure {
            log.warn("[recordScrapeFailure] 失败快照落库失败(忽略): pageId=${page.id}, err=${it.message}")
        }
    }

    companion object {
        fun elapsedMs(startedAt: Long): Int = (System.currentTimeMillis() - startedAt).toInt()

        /**
         * 抓取服务(scrapper)自身不可用 ≠ 目标站点失联。前者常见于本地开发没起 scrapper、
         * 鉴权 token 配错，此时若照旧把书签写成 UNREACHABLE，一次本地调试就能把好端端的
         * 书签洗成"失联"。这类失败一律不落库，直接向上抛出让调用方看到真实原因。
         */
        fun Throwable.isScrapperUnavailable(): Boolean =
            this is CommonException && errorType == ErrorType.E307

        /**
         * 我方**主动拒绝**去抓：目标不是域名(E309，裸 IP / localhost)，或目标指向内网(E308)。
         *
         * 与 [isScrapperUnavailable] 一样"不是站点的错"，因此同样不能落成 UNREACHABLE ——
         * `http://192.168.0.73:8192/` 那类书签在用户自己的网络里活得好好的，把我方的一个
         * 策略决定写成"失联"，用户看到的是自家服务被判了死刑。
         *
         * 处置方向却相反：抓取服务不可用是"稍后重来"，这个是"永远不会抓"，所以不该重试、
         * 也不该保留 PENDING。具体怎么收口按调用方分两种：
         *
         * - **人触发的**（后台的检测活性/重新获取/重抓资产）原样抛出，让管理员看到拒绝的理由；
         * - **异步链路上的**（`parseByApi`）就地标成 SUCCESS 收口，与 `parseBookmarkExclusively`
         *   里那道非域名前置过滤同一套处置。那里抛出反而有害：异步路径上没有人接，异常会一路
         *   冒泡到 `parseAndNotice` 的 runCatching，被它当成"未预期异常"写成 UNREACHABLE ——
         *   绕一圈回到本判据要防的那个结果。
         */
        fun Throwable.isRefusedTarget(): Boolean =
            this is CommonException && (errorType == ErrorType.E308 || errorType == ErrorType.E309)
    }
}
