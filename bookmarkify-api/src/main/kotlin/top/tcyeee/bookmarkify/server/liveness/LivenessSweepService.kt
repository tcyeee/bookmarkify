package top.tcyeee.bookmarkify.server.liveness

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.kotlin.KtUpdateWrapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.async.AsyncConfig
import top.tcyeee.bookmarkify.config.async.ParseLock
import top.tcyeee.bookmarkify.config.event.BookmarkParseEvent
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.SweepPreviewVO
import top.tcyeee.bookmarkify.entity.dto.BookmarkLivenessConfigValue
import top.tcyeee.bookmarkify.entity.entity.PageEntity
import top.tcyeee.bookmarkify.entity.entity.PagePingLogEntity
import top.tcyeee.bookmarkify.entity.entity.SiteEntity
import top.tcyeee.bookmarkify.entity.entity.SweepLogEntity
import top.tcyeee.bookmarkify.entity.enums.BookmarkLinkType
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.entity.enums.PingOutcome
import top.tcyeee.bookmarkify.mapper.PageMapper
import top.tcyeee.bookmarkify.mapper.PagePingLogMapper
import top.tcyeee.bookmarkify.mapper.SweepLogMapper
import top.tcyeee.bookmarkify.server.IApiService
import top.tcyeee.bookmarkify.server.IBookmarkLivenessConfigService
import top.tcyeee.bookmarkify.server.ISiteService
import top.tcyeee.bookmarkify.utils.WebsiteParser
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture

/**
 * 定时活性巡检的执行体。见 [ILivenessSweepService]。
 *
 * 本类只做**编排**：选候选、并发探测、按熔断结论决定要不要落库、推进调度游标、投递重新抓取。
 * 所有判据都是 [LivenessPolicy] 里的纯函数（三态映射、熔断、退避曲线、判失活、归档），
 * 调度列的写法收口在 [PageScheduleWriter]。这三者的分工是这块代码唯一需要记住的结构。
 */
@Service
class LivenessSweepService(
    private val pageMapper: PageMapper,
    private val pingLogMapper: PagePingLogMapper,
    private val sweepLogMapper: SweepLogMapper,
    private val siteService: ISiteService,
    private val apiService: IApiService,
    private val livenessConfigService: IBookmarkLivenessConfigService,
    private val scheduleWriter: PageScheduleWriter,
    private val parseLock: ParseLock,
    private val eventPublisher: ApplicationEventPublisher,
    @Qualifier(AsyncConfig.BOOKMARK_PARSE_EXECUTOR) private val parseExecutor: ThreadPoolTaskExecutor,
    @Qualifier(AsyncConfig.BOOKMARK_PING_EXECUTOR) private val pingExecutor: ThreadPoolTaskExecutor,
) : ILivenessSweepService {

    private val log = LoggerFactory.getLogger(javaClass)

    private fun pageQuery() = KtQueryWrapper(PageEntity::class.java)

    /**
     * 本轮巡检对某条记录掌握的**证据强度**。
     *
     * 存在的理由：站点层短路（见 [pingSweepExclusively]）会给一批页面直接安上 DEAD，而那不是
     * 本轮探测出来的，是上一轮站点结论的复用。这批"非证据"已经被排除在熔断样本之外，但
     * 失败计数与归档判定这两处一直照单全收 —— 于是一次站点级误判能在 10 个退避周期内把
     * 该域名下**所有**书签推进 ARCHIVED，而归档此前没有任何自动出口。
     */
    private data class ProbeEvidence(
        /** 本轮是否真的对这个页面发起了探测 */
        val directlyProbed: Boolean,
        /** 所属域名的连续失败次数，来自根地址的真实探测 */
        val siteConsecutiveFail: Int,
    )

    @Async(AsyncConfig.BOOKMARK_SWEEP_EXECUTOR)
    override fun retryUnreachableBookmarks() {
        // 一轮只读一次配置：getConfig() 走进程内缓存，但这一份还要透传给 onResult 回调，
        // 让整轮用的是同一个快照而不是可能被中途改写的两份
        val config = livenessConfigService.getConfig()
        // 职责范围为全部 UNREACHABLE（含已认证），不再按 verifyFlag 过滤：认证书签的重新解析仍会被
        // parseBookmark() 短路跳过，但 ping 结果本身依旧值得记录，且它是 UNREACHABLE 唯一的负责任务。
        pingSweep(
            taskLabel = TASK_RETRY_UNREACHABLE,
            statusFilter = ParseStatusEnum.UNREACHABLE,
            configuredIntervalHours = config.abnormalCheckIntervalHours,
            batchSize = RETRY_UNREACHABLE_BATCH_SIZE,
            // **UNKNOWN 也要触发重抓，只有 DEAD 才不试。**
            //
            // ping 只是个便宜探针（一个 HEAD），而抓取链路的能力严格更强：它有无头浏览器回退，
            // 还有 siteapi.rs 那级站点官方 API 救援。反爬站点（403/406/412）在 ping 侧必然是
            // UNKNOWN —— 判据只看得到"我们这次被拒了"，看不到"换个姿势能不能拿到"。
            //
            // 卡在 `== ALIVE` 的后果实测过：B 站视频页对机房 IP 恒返 412 → UNKNOWN → 永不重抓，
            // 而那恰恰是本项目投入最多精力去救的一类站点，`siteapi.rs` 整个模块就是为它写的。
            // 这些行本来就已经是 UNREACHABLE，多试一次几乎没有代价；真正的我方故障则由熔断
            // （UNKNOWN 过半即中止整轮）在更上游拦掉，不会演变成疯狂重试。
            triggeredParseOf = { bookmark, outcome -> outcome != PingOutcome.DEAD && !bookmark.verifyFlag },
        ) { bookmark, outcome, triggeredParse, evidence ->
            if (triggeredParse) {
                // 调度状态交给重新解析那条链路去写（成功回到正常周期、失败继续退避），
                // 这里抢着写只会被它覆盖，还会掩盖真实的失败次数
                val why = if (outcome == PingOutcome.ALIVE) "ping 成功" else "探测无结论，交给能力更强的抓取链路再试"
                log.debug("[$TASK_RETRY_UNREACHABLE] $why，触发重新解析: pageId=${bookmark.id}, urlHost=${bookmark.urlHost}")
            } else {
                val reason = when {
                    bookmark.verifyFlag -> "已手动认证，跳过重新解析"
                    outcome == PingOutcome.DEAD -> "ping 失败"
                    // 走到这里只可能是站点层短路（本轮没实际探测）或被背压推迟
                    else -> "本轮未实际探测，跳过重新解析"
                }
                log.debug("[$TASK_RETRY_UNREACHABLE] $reason: pageId=${bookmark.id}, urlHost=${bookmark.urlHost}")
                persistProbeResult(bookmark, outcome, config, evidence = evidence)
            }
        }
    }

    @Async(AsyncConfig.BOOKMARK_SWEEP_EXECUTOR)
    override fun livenessCheckStaleBookmarks() {
        // 同上：一轮只读一次配置
        val config = livenessConfigService.getConfig()
        // 职责范围收窄为 SUCCESS（含已认证）：UNREACHABLE 已由 retryUnreachableBookmarks 独占负责，
        // 避免同一条 UNREACHABLE 记录被两个任务重复 ping。PENDING 由 checkAll 负责，与此无关。
        pingSweep(
            taskLabel = TASK_LIVENESS_CHECK,
            statusFilter = ParseStatusEnum.SUCCESS,
            configuredIntervalHours = config.activeCheckIntervalHours,
            batchSize = LIVENESS_CHECK_BATCH_SIZE,
            triggeredParseOf = { bookmark, outcome -> shouldRefreshContent(bookmark, outcome, config) },
        ) { bookmark, outcome, triggeredParse, evidence ->
            if (triggeredParse) {
                // 同上：调度状态由重新解析那条链路负责写
                log.debug("[$TASK_LIVENESS_CHECK] 内容已过期，触发重新抓取: pageId=${bookmark.id}, urlHost=${bookmark.urlHost}")
            } else {
                val reason = when (outcome) {
                    PingOutcome.ALIVE -> "ping 成功且内容未过期，仅推进下次检查时间"
                    // 连续失败攒够 deadConfirmFailures 次才真的落 UNREACHABLE，见 persistProbeResult。
                    // 次数分两种取法，与那里的判据保持一致：本轮实际探过的用本页计数（那里
                    // advance 之后才 +1，所以这里 +1 才是本轮的值），站点层短路出来的
                    // 用域名计数（本页的计数按设计不再增长，打印它只会误导排查）
                    PingOutcome.DEAD -> {
                        val n = if (evidence.directlyProbed) bookmark.consecutiveFail + 1
                        else evidence.siteConsecutiveFail
                        "ping 失败(${if (evidence.directlyProbed) "本页第 $n 次" else "域名第 $n 次"})，" +
                            "达 ${config.deadConfirmFailures} 次方判失活"
                    }
                    // 无结论绝不能落库成 UNREACHABLE：那正是「一次抓取服务故障洗掉一批健康书签」的成因
                    PingOutcome.UNKNOWN -> "探测无结论，只做短退避"
                }
                log.debug("[$TASK_LIVENESS_CHECK] $reason: pageId=${bookmark.id}, urlHost=${bookmark.urlHost}")
                persistProbeResult(
                    bookmark, outcome, config,
                    // 这条巡检管的是 SUCCESS，是全系统唯一有资格把书签改判为失活的地方
                    mayConfirmDeath = true,
                    evidence = evidence,
                )
            }
        }
    }

    // ────── 手动触发一轮巡检 ──────

    /**
     * 两个巡检任务各自的口径，[sweepPreview] 与后台的确认框共用。
     *
     * 抽出来只为一件事：预览用的候选查询必须与 [pingSweepExclusively] 里那份**完全一致**
     * （同样的状态过滤、同样的 `next_check_at` 游标、同样的 LIMIT 和非域名过滤），否则确认框上
     * 写的"本轮 187 条"与真正跑出来的数对不上，这个功能就只剩误导。真正的执行体仍在各自的
     * [livenessCheckStaleBookmarks] / [retryUnreachableBookmarks] 里，这里不重复它们的策略。
     */
    private data class SweepSpec(
        val status: ParseStatusEnum,
        val batchSize: Int,
        val scope: String,
        /** 是否有资格把书签改判为失联 —— 确认框里最该写明的一条 */
        val mayConfirmDeath: Boolean,
        val intervalHoursOf: (BookmarkLivenessConfigValue) -> Int,
        /**
         * 这一条**有可能**顺带触发重新抓取吗。
         *
         * 只能给上界：真正的判据要等探测结果出来（见各自的 `triggeredParseOf`），而预览时
         * 一次都还没探。所以这里只滤掉"无论探成什么样都不会重抓"的那些（已手动认证的、
         * 存活巡检里内容还没过期的）。
         */
        val mayTriggerParse: (PageEntity, BookmarkLivenessConfigValue) -> Boolean,
    )

    /**
     * 只有仍在运行的两个任务。已下线的 `reviveArchivedBookmarks` 不在其中——它在历史轮次里还查得到，
     * 但没有执行体，可触发的清单必须按"现在真的跑得起来"来定，否则后台会给出一个点了没有任何反应的按钮。
     */
    private val sweepSpecs: Map<String, SweepSpec> = mapOf(
        TASK_LIVENESS_CHECK to SweepSpec(
            status = ParseStatusEnum.SUCCESS,
            batchSize = LIVENESS_CHECK_BATCH_SIZE,
            scope = "状态为「正常」的书签",
            mayConfirmDeath = true,
            intervalHoursOf = { it.activeCheckIntervalHours },
            // 与 livenessCheckStaleBookmarks 的 triggeredParseOf 同一个判据，按"探测结果为 ALIVE"取上界
            mayTriggerParse = { page, config -> shouldRefreshContent(page, PingOutcome.ALIVE, config) },
        ),
        TASK_RETRY_UNREACHABLE to SweepSpec(
            status = ParseStatusEnum.UNREACHABLE,
            batchSize = RETRY_UNREACHABLE_BATCH_SIZE,
            scope = "已判为「失联」的书签",
            mayConfirmDeath = false,
            intervalHoursOf = { it.abnormalCheckIntervalHours },
            // 这个任务只有 DEAD 不重抓，其余（含无结论）都会试，所以上界就是"没被手动认证过"
            mayTriggerParse = { page, _ -> !page.verifyFlag },
        ),
    )

    override fun sweepPreview(taskLabel: String): SweepPreviewVO {
        val spec = sweepSpecs[taskLabel] ?: throw CommonException(ErrorType.E102)
        val config = livenessConfigService.getConfig()
        val now = LocalDateTime.now()

        val backlog = pageMapper.selectCount(
            pageQuery().eq(PageEntity::parseStatus, spec.status).apply(DUE_CLAUSE, now)
        )

        val batch = pageMapper.selectList(
            pageQuery()
                .eq(PageEntity::parseStatus, spec.status)
                .apply(DUE_CLAUSE, now)
                .last("ORDER BY $DUE_CURSOR ASC LIMIT ${spec.batchSize}")
        )
        val candidates = batch.filter { WebsiteParser.classifyLinkType(it.urlHost) == BookmarkLinkType.DOMAIN }

        // 站点层短路的预测。真跑起来时根地址探通了的域名，其页面会回到逐页探测——
        // 所以 probes 是**下界**，worstCase 按"一个都没短路成"算。
        val siteMap = siteService.mapByIds(candidates.map { it.siteId })
        val (pagesOfDeadSites, pagesOfLiveSites) = candidates.partition { siteMap[it.siteId]?.isAlive == false }
        val rootProbes = pagesOfDeadSites.map { it.siteId }.distinct().size
        val probes = pagesOfLiveSites.size + rootProbes

        val perProbe = recentProbeCost(taskLabel)
        // 并发只压缩墙钟，不减少总工作量：估算按"分几批跑完"算，一批 PING_CONCURRENCY 条
        val waves = { n: Int -> Math.ceilDiv(n, AsyncConfig.PING_CONCURRENCY).toLong() }

        return SweepPreviewVO(
            taskLabel = taskLabel,
            scope = spec.scope,
            backlog = backlog,
            batchSize = spec.batchSize,
            candidates = candidates.size,
            truncated = (backlog - batch.size).coerceAtLeast(0),
            skippedNonDomain = batch.size - candidates.size,
            shortCircuited = pagesOfDeadSites.size,
            rootProbes = rootProbes,
            probes = probes,
            mayTriggerParse = pagesOfLiveSites.count { spec.mayTriggerParse(it, config) },
            mayConfirmDeath = spec.mayConfirmDeath,
            deadConfirmFailures = config.deadConfirmFailures,
            intervalHours = spec.intervalHoursOf(config),
            concurrency = AsyncConfig.PING_CONCURRENCY,
            // 历史均值本身已经包含了并发的效果（它是 durationMs/probed，墙钟除以条数），
            // 所以那条路径直接乘，不要再除一次并发度——除两次会把预估压到实际的八分之一
            estimatedMs = perProbe?.let { probes * it.msPerProbe } ?: (waves(probes) * ASSUMED_PROBE_MS),
            worstCaseMs = waves(candidates.size + rootProbes) * PING_TIMEOUT_MS,
            sampleProbeMs = perProbe?.msPerProbe,
            sampleRounds = perProbe?.rounds ?: 0,
            running = isSweepRunning(taskLabel),
        )
    }

    override fun isSweepRunning(taskLabel: String): Boolean = parseLock.isHeld(ParseLock.sweep(taskLabel))

    private data class ProbeCost(val msPerProbe: Long, val rounds: Int)

    /**
     * 从最近若干轮真实记录里估一条探测要多久（墙钟）。
     *
     * 用历史而不是拿"超时 15s ÷ 并发 8"去算：绝大多数探测在几百毫秒内就返回了，按超时算出来的
     * 预估会高出实际一个数量级，而一个永远报"预计 6 分钟"、实际 20 秒跑完的数字，管理员看两次
     * 就不会再信它。只统计 `probed > 0` 的轮次——空轮次的耗时是纯查询开销，混进来会把均值压到 0。
     */
    private fun recentProbeCost(taskLabel: String): ProbeCost? {
        val rounds = runCatching {
            sweepLogMapper.selectList(
                KtQueryWrapper(SweepLogEntity::class.java)
                    .eq(SweepLogEntity::taskLabel, taskLabel)
                    .gt(SweepLogEntity::probed, 0)
                    .orderByDesc(SweepLogEntity::createTime)
                    .last("LIMIT $PROBE_COST_SAMPLE_ROUNDS")
            )
        }.getOrElse { emptyList() }
        val probed = rounds.sumOf { it.probed }
        if (probed <= 0) return null
        return ProbeCost(msPerProbe = rounds.sumOf { it.durationMs } / probed, rounds = rounds.size)
    }

    /**
     * 这条已确认存活的书签是否该重新抓一次内容。
     *
     * 这是「书签更新」真正发生的地方。此前这里的条件是 `alive && !isActivity`，而
     * `isActivity=false` 在全代码里从不与 `parseStatus=SUCCESS` 共存（每一处置 false 的地方
     * 都同时写 UNREACHABLE），所以那个分支是死代码——正常站点改了标题、换了图标，线上永远
     * 不会重抓，只有管理员手动刷新才会更新。改为按内容陈旧度判定。
     *
     * 已手动认证(verifyFlag)的书签排除在外：那是人工确认过的终态，parseBookmark 也会短路跳过，
     * 投了事件只会白跑一趟并让这条记录每轮都被重新选中。
     */
    private fun shouldRefreshContent(
        bookmark: PageEntity,
        outcome: PingOutcome,
        config: BookmarkLivenessConfigValue,
    ): Boolean {
        if (outcome != PingOutcome.ALIVE || bookmark.verifyFlag) return false
        // 从未成功抓过内容（老数据 / 一直失败的记录）也算过期，正好借这一轮补齐
        val lastParseAt = bookmark.lastParseAt ?: return true
        return lastParseAt.isBefore(LocalDateTime.now().minusDays(config.contentRefreshIntervalDays.toLong()))
    }

    /**
     * 定时活性检测任务的通用骨架：按 [statusFilter] + 调度游标选出候选、逐条 ping、写 [PagePingLogEntity]，
     * 再交给 [onResult] 决定各自的落库/重新解析动作。[triggeredParseOf] 决定是否需要发布 [BookmarkParseEvent]。
     *
     * 候选只看 `next_check_at`，不再按 `update_time` 倒推时间窗：那一列还兼着「记录最近修改时间」，
     * 管理员改个标题就会把这条记录的下次巡检推迟一整个周期。`next_check_at` 为 NULL 的记录一律视为
     * 到期——宁可多查一次，也不能让一条没有调度状态的记录永久失踪。
     *
     * **先整批探测、再统一落库**，中间隔着一道熔断（[LivenessPolicy.breakerReason]）。顺序不能颠倒：
     * 熔断的判据是整批结果的形态，边探边写就来不及了——等发现异常时，前面几十条已经被写成失联。
     *
     * 候选总数（不含 LIMIT）超过 [batchSize] 时打印告警：说明当前数据量下，配置的检测间隔已经追不上，
     * 只是「目标值」而非「保证值」——需要调大 batchSize 或拉长间隔配置。[configuredIntervalHours]
     * 仅用于这条告警文案。
     */
    private fun pingSweep(
        taskLabel: String,
        statusFilter: ParseStatusEnum,
        configuredIntervalHours: Int,
        batchSize: Int,
        triggeredParseOf: (bookmark: PageEntity, outcome: PingOutcome) -> Boolean,
        onResult: (bookmark: PageEntity, outcome: PingOutcome, triggeredParse: Boolean, evidence: ProbeEvidence) -> Unit,
    ) {
        val lockKey = ParseLock.sweep(taskLabel)
        // 带凭据释放：一轮跑超 TTL 时，无条件 DEL 会把下一轮刚拿到的锁删掉，
        // 于是"跑得比预期慢"直接升级成"两轮并发跑"。见 ParseLock.acquire
        val token = parseLock.acquire(lockKey, SWEEP_LOCK_TTL) ?: run {
            log.warn("[$taskLabel] 上一轮巡检仍在进行(或另一实例正在跑)，本轮跳过")
            return
        }
        try {
            pingSweepExclusively(taskLabel, statusFilter, configuredIntervalHours, batchSize, triggeredParseOf, onResult)
        } finally {
            parseLock.release(lockKey, token)
        }
    }

    private fun pingSweepExclusively(
        taskLabel: String,
        statusFilter: ParseStatusEnum,
        configuredIntervalHours: Int,
        batchSize: Int,
        triggeredParseOf: (bookmark: PageEntity, outcome: PingOutcome) -> Boolean,
        onResult: (bookmark: PageEntity, outcome: PingOutcome, triggeredParse: Boolean, evidence: ProbeEvidence) -> Unit,
    ) {
        val startedAt = System.currentTimeMillis()
        val now = LocalDateTime.now()
        // 轮次 ID 在这里就定下来，而不是等 SweepLogEntity 构造时才生成：ping 日志比汇总行**先**落库
        // （汇总行的 durationMs 要等本轮跑完才知道），要让每条探测都带上自己属于哪一轮，
        // 这个 id 必须先于两者存在。见 PagePingLogEntity.sweepId
        val roundId = IdUtil.fastUUID()

        val totalBacklog = pageMapper.selectCount(
            pageQuery().eq(PageEntity::parseStatus, statusFilter).apply(DUE_CLAUSE, now)
        )
        if (totalBacklog > batchSize) {
            log.warn(
                "[$taskLabel] 候选积压 $totalBacklog 条，超过单次处理上限 $batchSize：" +
                    "当前数据量下 ${configuredIntervalHours}h 的检测间隔配置可能无法按时完成一轮检测"
            )
        }

        val batch = pageMapper.selectList(
            pageQuery()
                .eq(PageEntity::parseStatus, statusFilter)
                .apply(DUE_CLAUSE, now)
                // 最该查的优先处理，配合 LIMIT 保证积压记录会被逐批消费，不会被新记录饿死
                .last("ORDER BY $DUE_CURSOR ASC LIMIT $batchSize")
        )
        // 非域名类型(本地/IP/其他)不抓取，也不应对其发起存活 ping
        val (candidates, nonDomain) = batch.partition {
            WebsiteParser.classifyLinkType(it.urlHost) == BookmarkLinkType.DOMAIN
        }
        parkNonDomain(nonDomain, taskLabel)
        if (candidates.isEmpty()) {
            // **空轮次同样要留一行。** 「没有到期候选」是一个正常且有意义的事实，而它与
            // 「巡检压根没在跑」在数据上必须可区分 —— 后者正是 SweepHealthVO.lastRoundAt
            // 与后台那条常驻告警要发现的东西。这里直接 return 不落库的话，一个健康但空闲的
            // 系统（书签少、检测间隔又长时几乎总是空闲）会让 lastRoundAt 永远停在过去，
            // 于是告警条常亮"巡检已 N 小时没跑过" —— 一个总在响的警报等于没有警报。
            recordSweepRound(
                SweepLogEntity(
                    id = roundId,
                    taskLabel = taskLabel,
                    candidates = 0,
                    backlog = totalBacklog,
                    batchSize = batchSize,
                    probed = 0,
                    shortCircuited = 0,
                    shortCircuitedDead = 0,
                    aliveCount = 0,
                    deadCount = 0,
                    unknownCount = 0,
                    triggeredParse = 0,
                    deferredParse = 0,
                    durationMs = System.currentTimeMillis() - startedAt,
                ),
                taskLabel,
            )
            return
        }

        log.debug("[$taskLabel] 本次待检查书签数: ${candidates.size}")

        // ── 站点层短路 ──
        // 域名已经判定死亡的，不再逐页探测：一个挂掉的域名有 1000 个页面，就是 1000 次 15s 超时
        // 换同一个结论。每个这样的域名只对根地址探一次，看它是不是恢复了。
        val siteMap = siteService.mapByIds(candidates.map { it.siteId })
        val (pagesOfDeadSites, pagesOfLiveSites) = candidates.partition { siteMap[it.siteId]?.isAlive == false }
        val recovery = probeRoots(pagesOfDeadSites.mapNotNull { siteMap[it.siteId] })
        // 根地址通了 → 域名恢复，这些页面回到正常逐页探测的路径
        val revived = pagesOfDeadSites.filter { recovery[it.siteId] == PingOutcome.ALIVE }
        val shortCircuited: List<Pair<PageEntity, PingOutcome>> = pagesOfDeadSites
            .filterNot { recovery[it.siteId] == PingOutcome.ALIVE }
            // 根地址无结论（我方链路的问题）时给 UNKNOWN，不能记在站点账上
            .map { it to (recovery[it.siteId] ?: PingOutcome.UNKNOWN) }
        // 短路条数会并入本轮的汇总行，那里才是看走势的地方
        if (shortCircuited.isNotEmpty()) log.debug(
            "[$taskLabel] 站点已判定死亡，短路 ${shortCircuited.size} 个页面的探测（省下同样多次超时等待）"
        )

        // 并行探测。串行时最坏耗时是 batchSize × 单条超时(15s)，200 条要 50 分钟、贴着调度周期；
        // 并发度受 scrapper 的全局并发上限约束，见 AsyncConfig.PING_CONCURRENCY 的说明。
        val actuallyProbed: List<Pair<PageEntity, PingOutcome>> = (pagesOfLiveSites + revived)
            .map { bookmark ->
                bookmark to CompletableFuture.supplyAsync({ apiService.pingWebsite(bookmark.rawUrl) }, pingExecutor)
            }
            // 先全部投递、再统一 join：边投边等就退化成串行了
            .map { (bookmark, future) -> bookmark to future.join() }

        // **熔断只看真正探测过的结果。** 短路出来的那些 DEAD 不是探测结论，是上一轮的结论在复用；
        // 把它们混进来会凭空拉高失联比例，让「>90% DEAD」这条规则在一个健康的系统里误触发 ——
        // 而那条规则本来是用来发现"scrapper 通着但出口坏了、于是诚实地把一切报成死"的。
        //
        // 带上 urlHost：判据的比例按域名去重算，理由见 LivenessPolicy.breakerReason
        val breakerReason = LivenessPolicy.breakerReason(
            actuallyProbed.map { (page, outcome) -> LivenessPolicy.HostOutcome(page.urlHost, outcome) }
        )

        val probed = actuallyProbed + shortCircuited

        // ── 向解析池投递的背压 ──
        // 一轮最多 200 条候选，全都想重新抓取时就是 200 个事件一次性砸进解析池。那个池的队列
        // (500) 是和用户交互式 addOne 共享的，且满了以后走 CallerRunsPolicy —— 调用线程正是
        // 本方法所在的巡检线程，于是最长 60s 的抓取会在这里**同步**跑，200 条就是几小时，
        // 远超巡检锁 30 分钟的 TTL，下一个整点的轮次会与本轮并发跑。
        // 更隐蔽的一层：解析池最多 32 线程，一起打 scrapper 会顶穿它 32 的全局并发上限
        // (AsyncConfig.PING_CONCURRENCY 的注释里记着这条跨服务约束)，被 load_shed 打回 503
        // → E307 → parseByApi 直接原样返回、一个字段都不落库 → 这些行的 next_check_at 停在
        // 过去，永久占据 `ORDER BY next_check_at` 的队头。三件事在这里闭环成一个自我强化的
        // 坏循环，所以按队列余量截断，与 drainStuckLoading 守同一条规矩。
        val parseBudget = if (breakerReason != null) 0 else {
            (parseExecutor.threadPoolExecutor.queue.remainingCapacity() - SWEEP_PARSE_QUEUE_HEADROOM).coerceAtLeast(0)
        }
        var granted = 0

        // 哪些是站点层短路出来的（结论是复用的，不是本轮的新证据）。
        // 用 id 而不是引用比较：同一条记录在两个列表里是同一个对象，但显式一点更稳
        val shortCircuitedIds = shortCircuited.mapTo(HashSet()) { it.first.id }

        // 熔断时依旧落 ping 日志：这批结果本身就是判断「我方哪里坏了」的证据，
        // 不落等于把唯一的现场也丢了。只是 triggeredParse 全为 false，且不改动任何书签。
        val wantsParse = probed.map { (bookmark, outcome) ->
            breakerReason == null &&
                // **短路出来的行一律不触发重新抓取。** 这是所有巡检共通的不变式，不是某个任务的策略：
                // 本轮压根没探过它，那个 outcome 是上一轮站点结论的复用。
                // 一旦某个任务把判据放宽到 UNKNOWN 也触发（retryUnreachableBookmarks 就是），
                // 少了这一条，一个域名判死 + 根地址探测无结论，就会让该域名下**所有**页面
                // 同时投递重新抓取 —— 正好是站点层短路当初要省掉的那笔开销，被原样放大回来
                bookmark.id !in shortCircuitedIds &&
                triggeredParseOf(bookmark, outcome)
        }
        val triggeredParseOfEach = wantsParse.map { want ->
            (want && granted < parseBudget).also { if (it) granted++ }
        }
        val deferredCount = wantsParse.count { it } - granted
        if (deferredCount > 0) log.warn(
            "[$taskLabel] 解析队列余量不足，本轮推迟 $deferredCount 条重新抓取(可用额度=$parseBudget)，" +
                "这些记录会在 ${PageScheduleWriter.PROTECT_HOURS}h 后重新入选"
        )
        // 只为**真正探测过**的页面落 ping 日志：这张表的语义是"一次探测一行"，
        // 把短路的也写进去会让失联率、探测耗时这些基于它的统计全部失真。
        pingLogMapper.insert(
            actuallyProbed.mapIndexed { index, (bookmark, outcome) ->
                PagePingLogEntity(
                    pageId = bookmark.id,
                    urlHost = bookmark.urlHost,
                    outcome = outcome,
                    triggeredParse = triggeredParseOfEach[index],
                    sweepId = roundId,
                )
            }
        )

        // 一轮一行，无论正常完成、熔断，还是上面那种没有候选的空轮次。见 SweepLogEntity
        fun recordRound() = recordSweepRound(
            SweepLogEntity(
                // 与上面那批 ping 日志的 sweepId 是同一个值 —— 后台的下钻正是靠它对上
                id = roundId,
                taskLabel = taskLabel,
                candidates = candidates.size,
                backlog = totalBacklog,
                batchSize = batchSize,
                probed = actuallyProbed.size,
                shortCircuited = shortCircuited.size,
                shortCircuitedDead = shortCircuited.count { it.second == PingOutcome.DEAD },
                aliveCount = probed.count { it.second == PingOutcome.ALIVE },
                deadCount = probed.count { it.second == PingOutcome.DEAD },
                unknownCount = probed.count { it.second == PingOutcome.UNKNOWN },
                triggeredParse = granted,
                deferredParse = deferredCount,
                breakerReason = breakerReason?.take(500),
                durationMs = System.currentTimeMillis() - startedAt,
            ),
            taskLabel,
        )

        if (breakerReason != null) {
            log.error("[$taskLabel] 熔断，本轮不改动任何书签状态(仅推进 ${probed.size} 条的巡检游标): $breakerReason")
            // **熔断也必须推进游标。** 熔断的语义是"本轮的结论不可信，别拿它改书签"，不是
            // "本轮什么都没发生"。而候选是按 next_check_at 选的：一条都不推进，下一轮就会选出
            // **同一批**记录、得到同一个比例、再次熔断 —— 熔断从"跳过这一轮"退化成"永久停摆"，
            // 且没有任何自愈路径。2026-08-10 生产上连续 25 轮完全相同的记录就是这么来的。
            // 推 PageScheduleWriter.PROTECT_HOURS(1h) 而不是更久：巡检本身就是小时级的，
            // 我方链路修好后下一轮即可恢复，代价只是这批记录晚一个周期被判活性。
            probed.forEach { (page, _) -> scheduleWriter.protect(page.id) }
            recordRound()
            return
        }

        probed.forEachIndexed { index, (bookmark, outcome) ->
            val evidence = ProbeEvidence(
                directlyProbed = bookmark.id !in shortCircuitedIds,
                siteConsecutiveFail = siteMap[bookmark.siteId]?.consecutiveFail ?: 0,
            )
            when {
                triggeredParseOfEach[index] -> {
                    // **投递前先把游标推开。** 见 PageScheduleWriter.protect：调用方刻意把调度状态
                    // 交给解析链路去写，而那条链路有好几条不落库的出口
                    scheduleWriter.protect(bookmark.id)
                    onResult(bookmark, outcome, true, evidence)
                    eventPublisher.publishEvent(BookmarkParseEvent(bookmark.id))
                }
                // 想抓但被背压推迟：探测结论不落库（它只是"该重抓了"的依据，不是一个终态），
                // 只把游标推开一小段，等队列松快了下一轮再来。若走 onResult 的常规分支，
                // 这条 ALIVE 会被写成"正常周期"，于是一次队列拥堵会把这批记录推迟整整一个
                // 检测周期（默认 7 天），而它们本该是最急着被重抓的那批
                wantsParse[index] -> scheduleWriter.protect(bookmark.id)
                else -> onResult(bookmark, outcome, false, evidence)
            }
        }

        updateSiteLiveness(taskLabel, actuallyProbed, siteMap, recovery)

        // 一轮一条汇总，胜过几百条 debug：判断「检测间隔配置是否追得上」「站点失联率是否异常」
        // 靠的是这几个数字随时间的走势，逐条日志既翻不动也留不久。
        log.warn(
            "[$taskLabel] 本轮完成: 候选=${candidates.size}/积压=$totalBacklog, " +
                "实际探测=${actuallyProbed.size}/站点层短路=${shortCircuited.size}, " +
                "存活=${probed.count { it.second == PingOutcome.ALIVE }}, " +
                "失联=${probed.count { it.second == PingOutcome.DEAD }}, " +
                "无结论=${probed.count { it.second == PingOutcome.UNKNOWN }}, " +
                "触发重新抓取=${triggeredParseOfEach.count { it }}, " +
                "因队列拥堵推迟=$deferredCount, " +
                "耗时=${System.currentTimeMillis() - startedAt}ms"
        )
        recordRound()
    }

    /**
     * 落一行巡检轮次汇总。
     *
     * 熔断的语义是"我方链路坏了，本轮全表结论不可信"，它此前唯一的出口是一行 `log.error`，
     * 而日志会滚动、没人盯着。落成数据之后"最近一天熔断过几次"才是一句 SQL。
     *
     * 失败只记 warn：这是观测数据，写不进去不该反过来影响刚刚落库的巡检结果。
     */
    private fun recordSweepRound(round: SweepLogEntity, taskLabel: String) = runCatching {
        sweepLogMapper.insert(round)
    }.onFailure { log.warn("[$taskLabel] 巡检汇总落库失败(忽略): ${it.message}") }.let { }

    // ────── 站点层活性 ──────

    /**
     * 对一批站点的**根地址**各探一次，返回 siteId → 结论。
     *
     * 探根地址而不是探某个页面：判断的是"这个域名还在不在"，而具体页面 404 是常态。
     */
    private fun probeRoots(sites: List<SiteEntity>): Map<String, PingOutcome> {
        val distinct = sites.distinctBy { it.id }
        if (distinct.isEmpty()) return emptyMap()
        return distinct
            .map { site -> site to CompletableFuture.supplyAsync({ apiService.pingWebsite(site.rootUrl) }, pingExecutor) }
            .associate { (site, future) -> site.id to future.join() }
    }

    /**
     * 按本轮的页面探测结果推进 `site.is_alive`。
     *
     * 判定规则本身是纯函数 [LivenessPolicy.siteVerdict]（那里写着为什么"页面全挂"不等于"域名死了"）；
     * 这里只负责取数、按需补探根地址、落库。
     *
     * 补探根地址的时机刻意压到最小：只有「本轮全部页面失联、且该域名当前还被认为活着」的站点才
     * 需要一次根地址探测。健康的域名一次都不会多探。
     */
    private fun updateSiteLiveness(
        taskLabel: String,
        probed: List<Pair<PageEntity, PingOutcome>>,
        siteMap: Map<String, SiteEntity>,
        recovery: Map<String, PingOutcome>,
    ) = runCatching {
        // 站点层短路时对根地址的那次探测，两个方向都要落回来。
        //
        // ⚠️ **DEAD 也必须落，这是站点连续失败次数唯一的推进来源。** 下面的 needRootProbe 刻意
        // 跳过已经判死的域名（它们的根地址 recovery 刚探过，不必重复），于是一旦某域名判死，
        // 它就再也不会走到 siteVerdict 那条分支 —— 只回落 ALIVE 的话，`site.consecutiveFail`
        // 会**永久冻结**在判死那一刻的值。而站点层短路出来的页面，其判失活与归档判据读的
        // 正是这个计数（见 persistProbeResult：它们自己的 consecutiveFail 按设计不再增长），
        // 计数冻住就等于那批页面既到不了失活门槛、也到不了归档门槛，于是每轮都被重新选中、
        // 永久占据 `ORDER BY next_check_at ASC LIMIT n` 的队头，把真正该复查的记录饿死。
        //
        // 这次探测是对根地址的**直接**探测，是真证据，本来就该计数；UNKNOWN 除外——那是
        // 我方链路的问题，不能记在站点账上（与 PageScheduleWriter.advance 里同一条界线）。
        recovery.forEach { (siteId, outcome) ->
            when (outcome) {
                PingOutcome.ALIVE -> siteService.recordLiveness(siteId, alive = true)
                PingOutcome.DEAD -> siteService.recordLiveness(siteId, alive = false)
                PingOutcome.UNKNOWN -> {}
            }
        }

        val bySite = probed.filter { it.first.siteId.isNotBlank() }
            .groupBy { it.first.siteId }
            .mapValues { (_, group) -> group.map { it.second } }

        // 只有"页面全挂"的站点才需要根地址确认；判活那一侧不需要额外探测。
        // 已经是死的也不必再探：那批走的是站点层短路，recovery 刚探过。
        val needRootProbe = bySite
            .filterKeys { siteMap[it]?.isAlive != false }
            .filterValues { LivenessPolicy.siteVerdict(it, rootOutcome = null) == LivenessPolicy.SiteVerdict.UNCHANGED }
            .filterValues { outcomes -> outcomes.isNotEmpty() && outcomes.all { it == PingOutcome.DEAD } }
            .keys
        // 根页面本身就在本轮候选里时直接复用那条结果，别重复探一次
        val rootFromBatch = probed.filter { it.first.isRootPage }.associate { it.first.siteId to it.second }
        val rootOutcome = rootFromBatch + probeRoots(needRootProbe.filter { it !in rootFromBatch }.mapNotNull { siteMap[it] })

        var dead = 0
        var alive = 0
        var unchanged = 0
        bySite.forEach { (siteId, outcomes) ->
            when (LivenessPolicy.siteVerdict(outcomes, rootOutcome[siteId])) {
                LivenessPolicy.SiteVerdict.ALIVE -> {
                    // 本来就活着的不必重复写库，只有"从死转活"才是一次状态变更
                    if (siteMap[siteId]?.isAlive == false) {
                        siteService.recordLiveness(siteId, alive = true)
                        alive++
                    }
                }
                LivenessPolicy.SiteVerdict.DEAD -> {
                    siteService.recordLiveness(siteId, alive = false)
                    dead++
                }
                LivenessPolicy.SiteVerdict.UNCHANGED -> unchanged++
            }
        }
        if (dead > 0 || alive > 0) log.warn(
            "[$taskLabel] 站点活性更新: 经根地址确认死亡=$dead, 恢复存活=$alive, " +
                "证据不足保持原状=$unchanged(含『页面已消失但域名健在』), 补探根地址=${needRootProbe.size}"
        )
    }.onFailure {
        // 站点层活性只是优化探测开销的辅助信息，算错不该反过来影响已经落库的页面巡检结果
        log.warn("[$taskLabel] 站点活性更新失败(忽略): ${it.message}")
    }.let { }

    // ────── 巡检调度状态的推进 ──────

    /**
     * 把「本轮选中了、但按类型压根不该探测」的记录挪出候选队列。
     *
     * 非域名书签（`localhost` / 裸 IP / `chrome:` 等）会被 [WebsiteParser.classifyLinkType]
     * 过滤掉，而过滤发生在 `LIMIT` **之后** —— 它们照样占着候选查询的名额，且因为一路走不到
     * 任何写调度列的地方，`next_check_at` 永远停在过去，于是**永久**坐在
     * `ORDER BY next_check_at ASC` 的队头。2026-08-10 生产上 65 条到期候选里有 55 条是这种，
     * 只是当时 65 < 单轮上限 200 才没造成饥饿；再多 145 条这样的书签，真正该复查的记录就
     * 一条也选不出来了 —— 而这个故障没有任何症状，候选数看起来一直是满的。
     *
     * 推一年而不是一个周期：一条书签的 host 不会变（改网址会落到另一条 canonical 记录上），
     * 所以这个判断的结果是永久的，推短了只是让同一件事每周重来一次。也不推成 NULL 或更远：
     * `DUE_CURSOR` 把 NULL 当作"最该查"，而留一个能到期的时刻意味着万一分类规则将来放宽，
     * 这批记录还能自己回到巡检里，不需要一次数据订正。
     */
    private fun parkNonDomain(pages: List<PageEntity>, taskLabel: String) {
        if (pages.isEmpty()) return
        val now = LocalDateTime.now()
        // 一条 SQL 批量更新：这批可能有几百条，而它们既不产生探测也不产生日志，
        // 逐条 update 纯属白付数据库往返
        runCatching {
            pageMapper.update(
                null,
                KtUpdateWrapper(PageEntity::class.java)
                    .`in`(PageEntity::id, pages.map { it.id })
                    // 刻意不写 lastCheckAt：本轮并没有"检查"过它们，写了会让后台以为探过
                    .set(PageEntity::nextCheckAt, now.plusDays(NON_DOMAIN_PARK_DAYS))
            )
        }.onFailure {
            log.warn("[$taskLabel] 非域名候选的游标挪移失败(忽略): ${it.message}")
        }
        log.debug("[$taskLabel] ${pages.size} 条非域名书签不做探测，游标推后 $NON_DOMAIN_PARK_DAYS 天")
    }

    /**
     * 把一次纯探测（没有抓取内容）的结论落库：只动调度列，必要时再改状态。
     *
     * [mayConfirmDeath] 由调用方给出，表示「这条巡检**有资格**把记录从可用改判为失联」，
     * 只有 SUCCESS 那条巡检会传 true（UNREACHABLE 巡检里记录本来就已经是失联了）。
     * 有资格不等于就这么办：还要 [LivenessPolicy.confirmsDead] 点头，也就是连续失败次数
     * 已经攒够 `deadConfirmFailures` 次。**单次探测失败不判死**——一次机房出口抖动
     * 不该让一批好端端的书签在用户桌面上集体变灰。
     *
     * 归档与上面两者都无关：
     *
     * 连续失败累计到阈值就转 [ParseStatusEnum.ARCHIVED]，这条记录从两个巡检任务的候选池里彻底
     * 移出（各自只认 UNREACHABLE / SUCCESS），不再无休止地每半个月 ping 一个早就没了的域名。
     * **失败次数是在 UNREACHABLE 巡检里一轮轮累积起来的，而那条路径的 mayConfirmDeath 恒为 false**
     * ——所以归档判定必须独立于它，否则永远只在「首次失联」那一刻检查阈值（那时计数才 1），
     * 归档实际上一次都不会发生。
     *
     * 用户侧没有新语义：`isActivity=false` 不变，照旧算失效书签，归档只是停止巡检。
     */
    private fun persistProbeResult(
        bookmark: PageEntity,
        outcome: PingOutcome,
        config: BookmarkLivenessConfigValue,
        mayConfirmDeath: Boolean = false,
        evidence: ProbeEvidence,
    ) {
        scheduleWriter.advance(bookmark, outcome, directlyProbed = evidence.directlyProbed)
        // 判死与归档看的是同一份「连续失败次数」，取法也必须一致：
        // 站点层短路的页面本轮没被探测，它的 consecutiveFail 已经不再增长（见 PageScheduleWriter.advance），
        // 于是永远够不到任何阈值。这一支改由**域名**的连续失败次数供证 —— 那来自根地址的
        // 真实探测，是这条路径上唯一的新证据。少了它，一个域名下上千个页面会永远
        // 停在候选池里，每轮吃掉 LIMIT 名额，把真正该复查的记录挤出去
        val failures =
            if (evidence.directlyProbed) bookmark.consecutiveFail else evidence.siteConsecutiveFail
        val archived = outcome == PingOutcome.DEAD &&
            LivenessPolicy.shouldArchive(failures, config.maxRetryFailures)
        val markUnreachable = mayConfirmDeath && outcome == PingOutcome.DEAD &&
            LivenessPolicy.confirmsDead(failures, config.deadConfirmFailures)
        val update = KtUpdateWrapper(PageEntity::class.java)
            .eq(PageEntity::id, bookmark.id)
            .set(PageEntity::lastCheckAt, bookmark.lastCheckAt)
            .set(PageEntity::nextCheckAt, bookmark.nextCheckAt)
            .set(PageEntity::consecutiveFail, bookmark.consecutiveFail)
        if (archived) {
            // 归档是**终态**：没有任何定时任务再选它（三条巡检各自只认 SUCCESS / UNREACHABLE /
            // PENDING）。游标仍然要往前推一格而不是留在过去 —— 它不再被查询用到，但一个停在
            // 几个月前的 next_check_at 会让后台的「下次检查」列读起来像是巡检卡住了。
            // 复活的唯一入口是新用户添加该网址，见 BookmarkServiceImpl.reviveOnAdd
            update.set(
                PageEntity::nextCheckAt,
                LocalDateTime.now().plusDays(ARCHIVE_RECHECK_DAYS)
            )
        }
        if (markUnreachable || archived) {
            if (archived) {
                log.warn(
                    "[persistProbeResult] 连续失败 ${bookmark.consecutiveFail} 次，转入归档不再巡检: " +
                        "pageId=${bookmark.id}, urlHost=${bookmark.urlHost}"
                )
            }
            update.set(PageEntity::parseStatus, if (archived) ParseStatusEnum.ARCHIVED else ParseStatusEnum.UNREACHABLE)
                .set(PageEntity::isActivity, false)
                // 状态真的变了，这才算记录被修改
                .set(PageEntity::updateTime, LocalDateTime.now())
        }
        pageMapper.update(null, update)
    }

    companion object {
        /**
         * 任务标签。这三处必须是同一个字符串：`@Async` 方法自己的日志前缀、[sweepSpecs] 的键、
         * `sweep_log.task_label` 落库的值 —— 后台的手动触发按钮按这个值路由，历史轮次也按它筛。
         * 写成常量而不是三处字面量，是因为拼错了不会报错，只会让后台某个任务永远查不到轮次记录。
         */
        const val TASK_LIVENESS_CHECK = "livenessCheckStaleBookmarks"
        const val TASK_RETRY_UNREACHABLE = "retryUnreachableBookmarks"

        /**
         * 巡检候选的调度游标表达式。
         *
         * `next_check_at IS NULL` 一律视为「到期」（宁可多查一次，也不能让一条没有调度状态的
         * 记录永久失踪），但把它写成 `next_check_at <= ? OR next_check_at IS NULL` 有两个代价：
         * 那个 OR 不可 sarg，而 `ORDER BY next_check_at ASC NULLS FIRST` 又与 btree 升序默认的
         * NULLS LAST 相反 —— 两件事合起来意味着每一轮的 count 与 select 都要把该状态下的全部
         * 记录排一遍。折成 COALESCE 之后谓词与排序用的是同一个表达式，正好配一条表达式索引
         * (`idx_page_due_check`)，也和 `checkAll` 里 `COALESCE(update_time, create_time)`
         * 的既有写法一致。
         *
         * 纪元时间充当「从未调度过」的哨兵：它永远小于任何 now，语义上就是最优先。
         */
        private const val DUE_CURSOR = "COALESCE(next_check_at, TIMESTAMP '1970-01-01')"
        private const val DUE_CLAUSE = "$DUE_CURSOR <= {0}"
        private const val RETRY_UNREACHABLE_BATCH_SIZE = 50
        private const val LIVENESS_CHECK_BATCH_SIZE = 200

        // ── 手动触发前的耗时预估（只影响确认框里那个数字，不影响任何执行逻辑）──
        // 单条探测的超时，与 ApiServiceImpl.PING_READ_TIMEOUT_MS 对齐。只用来算「最坏耗时」：
        // 一批全部吃满超时的情况实际上只在 scrapper 整个不可达时出现。
        private const val PING_TIMEOUT_MS = 15_000L
        // 没有历史轮次可参考时的单条探测假设值。取 3s 而不是超时值：绝大多数探测在一秒内返回，
        // 按 15s 估会给出一个高出实际一个数量级的数字，那样的预估没人会再看第二次。
        private const val ASSUMED_PROBE_MS = 3_000L
        // 估算单条探测耗时时回看多少轮。够摊平个别慢轮，又不至于把几天前的链路状况算进来。
        private const val PROBE_COST_SAMPLE_ROUNDS = 20

        // 归档时把调度游标往前推的幅度。归档后没有任何巡检会再选中这条记录（复活的唯一入口是
        // 新用户添加该网址），所以这个值不影响任何查询——它只是不让后台的「下次检查」列
        // 停在一个几个月前的时间点上，那读起来像是巡检卡住了
        private const val ARCHIVE_RECHECK_DAYS = 30L
        // 非域名书签（本地/IP）被选中却无法探测时，游标推多远。见 parkNonDomain
        private const val NON_DOMAIN_PARK_DAYS = 365L
        // 巡检向解析池投递时留出的余量。比 BookmarkServiceImpl 的 DRAIN_QUEUE_HEADROOM 更保守：
        // 补投递面向的是用户桌面上正在转圈的格子（有人在等），而巡检的重新抓取是纯后台内容刷新
        //（没人在等），两者抢同一个队列时，该让路的显然是后者。
        private const val SWEEP_PARSE_QUEUE_HEADROOM = 150
        // 巡检锁的存活时间。取值要大于「一轮巡检的最坏耗时」——并行 8 路、单条超时 15s、
        // 批量 200 条，理论最坏约 6~7 分钟，再留足富余；同时必须小于调度周期(1h)，
        // 否则进程被强杀后这把锁会一直挡住后续所有轮次。
        private val SWEEP_LOCK_TTL: Duration = Duration.ofMinutes(30)
    }
}
