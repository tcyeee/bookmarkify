package top.tcyeee.bookmarkify.server.liveness

import top.tcyeee.bookmarkify.entity.enums.PingOutcome
import java.time.LocalDateTime

/**
 * 活性巡检的**纯策略**：只做判断，不碰数据库、不发网络请求。
 *
 * 与 [top.tcyeee.bookmarkify.server.asset.AssetRolePolicy] 同样的用意——把"规则"从"流程"里摘出来，
 * 这样阈值和退避曲线可以被单元测试直接钉住，而不必起一个 Spring 上下文去验证一个算术表达式。
 */
object LivenessPolicy {

    // ────── 熔断阈值 ──────

    /** 「探不到」过半就该认为是我方的问题，而不是这半批站点恰好都出了状况 */
    private const val UNKNOWN_PERCENT = 50
    private const val MIN_SAMPLE_UNKNOWN = 10

    /**
     * 判死的阈值取得比 UNKNOWN 高得多：这批候选本来就是「久未检查」的记录，
     * 真混进一些死站点很正常，只有接近全灭才反常到只能是我方链路故障。
     */
    private const val DEAD_PERCENT = 90
    private const val MIN_SAMPLE_DEAD = 20

    /**
     * 整批探测结果是否呈现「系统性失败」的形态；非 null 表示应当熔断，内容是给日志的原因。
     *
     * 两条判据针对两种不同的故障，缺一不可：
     * - **UNKNOWN 占比高**：抓取服务没起 / 鉴权配错 / 被限流，我方压根没探到东西。
     * - **DEAD 占比高**：抓取服务活着，但它的出网链路（代理）断了——此时它会**诚实地**返回
     *   `alive=false`，全是合法的 2xx 响应，上一条判据完全看不见。而现实中一批随机站点
     *   不会在同一小时集体死亡，这种形态只可能是我方的问题。
     *
     * 两者都要求最小样本量，否则一轮只有两三条候选、恰好都是死站点时会误触发。
     */
    fun breakerReason(outcomes: List<PingOutcome>): String? {
        val total = outcomes.size
        val unknown = outcomes.count { it == PingOutcome.UNKNOWN }
        val dead = outcomes.count { it == PingOutcome.DEAD }
        return when {
            total >= MIN_SAMPLE_UNKNOWN && unknown * 100 >= total * UNKNOWN_PERCENT ->
                "无结论 $unknown/$total 已达 $UNKNOWN_PERCENT%，抓取服务多半整体不可用"
            total >= MIN_SAMPLE_DEAD && dead * 100 >= total * DEAD_PERCENT ->
                "判定失联 $dead/$total 已达 $DEAD_PERCENT%，多半是我方出网链路故障而非站点集体下线"
            else -> null
        }
    }

    // ────── 退避曲线 ──────

    /**
     * 退避的指数上限。24h 的基础间隔配上 2^4 = 16 倍，最长约 16 天一次；
     * 再往上拉长意义不大——[ARCHIVE_AFTER_FAILURES] 会先把这条记录移出候选池。
     */
    private const val MAX_BACKOFF_EXPONENT = 4

    /** 无结论时的重试间隔：故障多半是分钟级到小时级的，1 小时后再看一眼即可。 */
    private const val UNKNOWN_RETRY_HOURS = 1L

    /**
     * 连续失败到这个次数就归档：按 24h 起步的退避曲线算，累计已经探测了两个多月。
     * 到这一步与其继续每半个月 ping 一次，不如移出候选池、交给管理员批量清理——
     * 否则一年之后候选池最前面全是尸体，配合 LIMIT 会把真正该复查的记录挤掉。
     */
    const val ARCHIVE_AFTER_FAILURES = 10

    /**
     * 下一次巡检的时间点。
     *
     * [consecutiveFail] 传**本次结论已经计入之后**的值（失败则已 +1，成功则已归零）。
     * [PingOutcome.UNKNOWN] 不参与退避：那是我方链路的问题，不该记在站点账上，
     * 否则一次抓取服务故障就能把全表推到退避曲线的末端，之后半个月都不再复查。
     */
    fun nextCheckAt(
        now: LocalDateTime,
        outcome: PingOutcome,
        consecutiveFail: Int,
        activeIntervalHours: Int,
        abnormalIntervalHours: Int,
    ): LocalDateTime = when (outcome) {
        PingOutcome.ALIVE -> now.plusHours(activeIntervalHours.toLong())
        PingOutcome.UNKNOWN -> now.plusHours(UNKNOWN_RETRY_HOURS)
        PingOutcome.DEAD -> {
            val exponent = (consecutiveFail - 1).coerceIn(0, MAX_BACKOFF_EXPONENT)
            now.plusHours(abnormalIntervalHours.toLong() shl exponent)
        }
    }

    /** 连续失败次数是否已经到了该归档的程度。 */
    fun shouldArchive(consecutiveFail: Int): Boolean = consecutiveFail >= ARCHIVE_AFTER_FAILURES

    // ────── 站点（域名）级活性 ──────

    /** 一轮巡检对某个域名的结论。 */
    enum class SiteVerdict {
        /** 域名确认活着 */
        ALIVE,

        /** 域名确认死亡：**只有根地址探测也失败时才会给出** */
        DEAD,

        /** 证据不足，保持原状 */
        UNCHANGED,
    }

    /**
     * 由「该域名下页面的探测结果」+「根地址的探测结果」判定域名活性。
     *
     * ## 这个函数存在的全部理由
     *
     * > **「某域名本轮所有候选页面都探测失败」不足以判定域名死亡。**
     *
     * 用户收藏的往往就是深链，而深链失效（视频被删、仓库归档、文章下架）是最常见的情形，
     * 与域名死活无关。一旦一个被删的视频就能把 `youtube.com` 判死，下一轮该域名下**所有**页面
     * 都会被站点层短路成失联、不再实际探测 —— 一次误判级联成整站误判，且再没有探测能纠正它。
     * 与 [breakerReason] 防的是同一类事故：不要让局部证据推出全局结论。
     *
     * 所以判死这一侧必须由根地址确认；而判活不需要 —— 任意一个页面通了，域名必然活着。
     *
     * @param pageOutcomes 该域名下本轮**实际探测过**的页面结论（短路出来的不算，那是旧结论的复用）
     * @param rootOutcome 根地址的探测结论；`null` 表示没探（那就只可能是 [UNCHANGED]）
     */
    fun siteVerdict(pageOutcomes: List<PingOutcome>, rootOutcome: PingOutcome?): SiteVerdict = when {
        // 任意页面存活 → 域名必然存活，不必看根地址
        pageOutcomes.any { it == PingOutcome.ALIVE } -> SiteVerdict.ALIVE
        // 没探到任何页面，无从判断
        pageOutcomes.isEmpty() -> SiteVerdict.UNCHANGED
        // 混着 UNKNOWN（我方链路的问题）就不算"全部失联"，证据不足
        !pageOutcomes.all { it == PingOutcome.DEAD } -> SiteVerdict.UNCHANGED
        // 全部页面失联，结论完全取决于根地址
        else -> when (rootOutcome) {
            PingOutcome.DEAD -> SiteVerdict.DEAD
            // 根地址通着 → 页面是真的没了，域名健在
            PingOutcome.ALIVE -> SiteVerdict.UNCHANGED
            else -> SiteVerdict.UNCHANGED
        }
    }
}
