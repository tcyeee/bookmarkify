package top.tcyeee.bookmarkify.server.liveness

import top.tcyeee.bookmarkify.entity.dto.BookmarkLivenessConfigValue
import top.tcyeee.bookmarkify.entity.enums.PingOutcome
import java.time.LocalDateTime

/**
 * 活性巡检的**纯策略**：只做判断，不碰数据库、不发网络请求。
 *
 * 与 [top.tcyeee.bookmarkify.server.asset.AssetRolePolicy] 同样的用意——把"规则"从"流程"里摘出来，
 * 这样阈值和退避曲线可以被单元测试直接钉住，而不必起一个 Spring 上下文去验证一个算术表达式。
 */
object LivenessPolicy {

    // ────── 由探测事实判定死活 ──────

    /**
     * 「这个页面已经没了」。
     *
     * 只收这两个：服务器明确表态该资源不存在(404)、或曾经存在而已被永久移除(410)。
     * 这是本策略**唯一**的判死来源里最重要的一条——用户口中"这个书签打不开"，绝大多数
     * 是深链失效（视频被删、文章下架、仓库归档），而不是整个域名消失。
     */
    private val GONE_STATUSES = setOf(404, 410)

    /**
     * 「探不出结论」，既不判活也不判死。
     *
     * 这几个码的共同点是：站点活得好好的，只是**拒绝了我们这一次请求**。
     * - 403/406/412：典型的反爬拦截。生产机实测过，机房出口 IP 会被整段拒绝，而同一个
     *   URL 在用户浏览器里完全正常——判死就是拿我方的网络位置给用户的书签定罪。
     * - 429：限流，多半还是我们自己探得太密。
     * - 451：法律原因下架，是地域性的，同样不是"这个页面没了"。
     *
     * 判成 UNKNOWN 而非 DEAD 有两层保护：不写 `UNREACHABLE`，也不累加 `consecutiveFail`，
     * 于是这类站点不会一路走到归档。
     */
    private val INCONCLUSIVE_STATUSES = setOf(403, 406, 412, 425, 429, 451)

    /**
     * 把 scrapper 报回来的**事实**折叠成一个结论。
     *
     * 这个映射此前住在 scrapper 里（`alive = status < 500`），和 `extractor`→`role`
     * 一样属于放错了服务的策略。搬回来之后，改判定规则不需要动 Rust、不需要重新探测。
     *
     * ⚠️ **[blocked] 必须判 UNKNOWN。** 那表示 scrapper 的 SSRF 策略拒绝去探这个地址——
     * 是我方的安全决策，不是站点的事实。这与 `classifyScrapperError` 里 E308 刻意不并进
     * E304 是同一条界线：「我们拒绝抓它」不能当作「它挂了」的证据。
     *
     * @param reachable 是否拿到了 HTTP 响应（传输层是否成功）
     * @param status 最终一跳的状态码；[reachable] 为 false 时无意义
     * @param blocked 被 scrapper 的 SSRF 策略拒绝
     */
    fun outcomeOf(reachable: Boolean, status: Int?, blocked: Boolean): PingOutcome = when {
        blocked -> PingOutcome.UNKNOWN
        // 传输层就没成功：DNS 没了、连接被拒、TLS 失败、超时。这是站点的事实。
        // 我方出网链路整体故障时同样会大面积落到这里，那一层由 breakerReason 的
        // 「DEAD 近全灭」规则兜住——单条无从分辨，整批的形态才分辨得出来
        !reachable -> PingOutcome.DEAD
        // 拿到了响应却没有状态码，只可能是契约对不上，属于我方问题
        status == null -> PingOutcome.UNKNOWN
        status in GONE_STATUSES -> PingOutcome.DEAD
        status in INCONCLUSIVE_STATUSES -> PingOutcome.UNKNOWN
        // 5xx：服务器在，但它自己提供不了服务。瞬时故障的代价只是一格退避，
        // 下一次探活即把 consecutiveFail 归零，不会误伤到归档
        status >= 500 -> PingOutcome.DEAD
        // 其余 2xx/3xx，以及 401(要登录)、405(不认这个方法) 等等，都说明页面还在
        else -> PingOutcome.ALIVE
    }

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

    /** 一次探测的「哪个域名 → 什么结论」。[breakerReason] 需要域名，见那里的说明。 */
    data class HostOutcome(val host: String, val outcome: PingOutcome)

    /**
     * 整批探测结果是否呈现「系统性失败」的形态；非 null 表示应当熔断，内容是给日志的原因。
     *
     * 两条判据针对两种不同的故障，缺一不可：
     * - **UNKNOWN 占比高**：抓取服务没起 / 鉴权配错 / 被限流，我方压根没探到东西。
     * - **DEAD 占比高**：抓取服务活着，但它的出网链路（代理）断了——此时它会**诚实地**返回
     *   `alive=false`，全是合法的 2xx 响应，上一条判据完全看不见。而现实中一批随机站点
     *   不会在同一小时集体死亡，这种形态只可能是我方的问题。
     *
     * ## 比例按**域名**算，样本量按**探测次数**算
     *
     * 两个判据要回答的都是「这是不是我方的全局故障」，而一个域名下有几条书签纯属用户的收藏
     * 习惯，与故障范围毫无关系。按探测次数算比例，等于让收藏最多的那个站点决定全局结论 ——
     * 2026-08-10 生产上就是这么锁死的：10 条候选里 4 条是同一个 B 站视频页，B 站对机房出口
     * IP 的内容页一律回 412（判 UNKNOWN，且是**确定性**的，123 次探测 123 次如此），再加
     * zfrontier、reddit 各一条，恒定 6/10 = 60% 触发熔断。而熔断的出口在落库之前，
     * `next_check_at` 一条也不推进，于是下一个整点选出来的还是这 10 条、还是这个比例 ——
     * 连续 25 轮字节级相同，全系统再没有任何一条书签被判过活性。按域名去重是 3/7 = 43%，
     * 不触发；真正的我方故障则是**所有**域名一起探不到，去重后反而更接近 100%，判得更准。
     *
     * 样本量仍按探测次数算：它防的是「一轮只剩两三条候选、恰好都是死站点」的误触发，
     * 那是个关于证据量的问题，与域名分布无关。
     *
     * 某个域名下混着 ALIVE 的，既不算 UNKNOWN 也不算 DEAD 域名 —— 有一页通了就说明这个域名
     * 我方够得着，它恰恰是「全局故障」这个结论的反证。
     */
    fun breakerReason(probes: List<HostOutcome>): String? {
        val total = probes.size
        if (total == 0) return null
        val byHost = probes.groupBy { it.host }
        val hosts = byHost.size
        fun hostsAll(outcome: PingOutcome) = byHost.count { (_, group) -> group.all { it.outcome == outcome } }
        val unknownHosts = hostsAll(PingOutcome.UNKNOWN)
        val deadHosts = hostsAll(PingOutcome.DEAD)
        return when {
            total >= MIN_SAMPLE_UNKNOWN && unknownHosts * 100 >= hosts * UNKNOWN_PERCENT ->
                "无结论 $unknownHosts/$hosts 个域名（共 $total 次探测）已达 $UNKNOWN_PERCENT%，抓取服务多半整体不可用"
            total >= MIN_SAMPLE_DEAD && deadHosts * 100 >= hosts * DEAD_PERCENT ->
                "判定失联 $deadHosts/$hosts 个域名（共 $total 次探测）已达 $DEAD_PERCENT%，多半是我方出网链路故障而非站点集体下线"
            else -> null
        }
    }

    // ────── 判死的确认门槛 ──────

    /**
     * 「探测失败到这个次数，才可以把书签落成失活」。
     *
     * ## 为什么单次失败不足以判死
     *
     * 一次 `HEAD` 探测失败的成因里，只有一部分是「站点真的没了」：目标服务重启、我方机房出口
     * 抖动、CDN 换节点、对端限流到把连接直接断掉，都会产生一次一模一样的失败。而判死是有
     * **用户可见后果**的（书签变灰、`isActivity=false`），拿单次证据去做这个动作，等价于把
     * 我方网络的每一次打嗝广播给所有用户。
     *
     * 要求连续 N 次的代价很小：这 N 次之间隔着退避曲线，期间书签对用户照常显示为可用，
     * 唯一的损失是「真死的站点晚几天变灰」——而那本来就没有时效性。
     *
     * [required] 由管理员配置（`deadConfirmFailures`，默认 3）。传 0 或负数按 1 处理，
     * 也就是退回「一次失败即判死」，而不是变成永远判不了死。
     *
     * @param consecutiveFail **本次结论已经计入之后**的连续失败次数
     */
    fun confirmsDead(consecutiveFail: Int, required: Int): Boolean =
        consecutiveFail >= required.coerceAtLeast(1)

    // ────── 退避曲线 ──────

    /** 无结论时的重试间隔：故障多半是分钟级到小时级的，1 小时后再看一眼即可。 */
    private const val UNKNOWN_RETRY_HOURS = 1L

    /**
     * [BookmarkLivenessConfigValue.maxRetryFailures] 的下限。
     *
     * 归档必须发生在判失活**之后**：`deadConfirmFailures` 的下限是 1，所以这个数至少要是 2，
     * 否则记录会在被标成 `UNREACHABLE` 之前先归档，而 `retryUnreachableBookmarks` 正是按
     * 那个状态选候选的 —— 「失活」这个状态永远不会出现，那条巡检也就永远找不到候选。
     */
    const val MIN_MAX_RETRY_FAILURES = 2

    /**
     * 下一次巡检的时间点。
     *
     * [consecutiveFail] 传**本次结论已经计入之后**的值（失败则已 +1，成功则已归零）。
     * [PingOutcome.UNKNOWN] 不参与退避：那是我方链路的问题，不该记在站点账上，
     * 否则一次抓取服务故障就能把全表推到退避曲线的末端，之后半个月都不再复查。
     *
     * 退避的三个参数（基数 / 倍数 / 上限）全部来自 [BookmarkLivenessConfigValue]。整体传配置
     * 而不是散着传四五个 Int：这几个值之间是有约束的（上限不能小于基数），拆成位置参数之后
     * 调用方传反了不会有任何编译期提示，而算错的后果——某一批记录再也不被复查——是静默的。
     */
    fun nextCheckAt(
        now: LocalDateTime,
        outcome: PingOutcome,
        consecutiveFail: Int,
        config: BookmarkLivenessConfigValue,
    ): LocalDateTime = when (outcome) {
        PingOutcome.ALIVE -> now.plusHours(config.activeCheckIntervalHours.toLong().coerceAtLeast(1))
        PingOutcome.UNKNOWN -> now.plusHours(UNKNOWN_RETRY_HOURS)
        PingOutcome.DEAD -> now.plusHours(backoffHours(consecutiveFail, config))
    }

    /**
     * 第 [consecutiveFail] 次失败对应的重试间隔（小时）。
     *
     * 写成循环而不是 `base * multiplier.pow(n)`：`consecutiveFail` 是从库里读出来的，
     * 历史数据里出现一个很大的值并非不可能，而幂运算会直接溢出成负数——那算出来的
     * `next_check_at` 落在过去，这条记录会永久占据候选队列的队头，把后面的全部饿死
     * （与 `protectSchedule` 防的是同一类事故）。逐次相乘并在触顶时立刻返回，
     * 无论传进来什么值都不会越过上限。
     */
    private fun backoffHours(consecutiveFail: Int, config: BookmarkLivenessConfigValue): Long {
        val base = config.abnormalCheckIntervalHours.toLong().coerceAtLeast(1)
        // 上限配得比基数还小时以基数为准：宁可退化成固定间隔，也不能因为一个配置笔误
        // 让所有死站点变成每小时重探一次
        val cap = config.abnormalMaxIntervalHours.toLong().coerceAtLeast(base)
        // 倍数取 1 即固定间隔；小于 1 没有语义，按 1 处理
        val multiplier = config.abnormalBackoffMultiplier.toLong().coerceAtLeast(1)
        // 倍数为 1 时曲线是平的，下面的循环永远碰不到"触顶即返回"那道出口，
        // 于是 consecutiveFail 有多大就空转多少次 —— 而这是在巡检线程上跑的
        if (multiplier == 1L) return base.coerceAtMost(cap)
        // 理论上 DEAD 一定伴随 fail>=1，但这里不该依赖调用方的纪律
        val steps = (consecutiveFail - 1).coerceAtLeast(0)
        var hours = base
        repeat(steps) {
            if (hours >= cap) return cap
            hours *= multiplier
        }
        return hours.coerceAtMost(cap)
    }

    /**
     * 连续失败次数是否已经到了该归档的程度，也就是「重试到头了」。
     *
     * [maxRetryFailures] 来自管理员配置。低于 [MIN_MAX_RETRY_FAILURES] 时按下限处理而不是
     * 按传入值处理：这个函数是在巡检线程上按行调用的，配置读坏（JSON 解析失败退回默认值、
     * 或历史上写进过一个 0）不该让整张表在一轮之内集体归档 —— 那个动作没有自动的撤销路径。
     */
    fun shouldArchive(consecutiveFail: Int, maxRetryFailures: Int): Boolean =
        consecutiveFail >= maxRetryFailures.coerceAtLeast(MIN_MAX_RETRY_FAILURES)

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
