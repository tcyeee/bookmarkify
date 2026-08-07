package top.tcyeee.bookmarkify.server.liveness

import top.tcyeee.bookmarkify.entity.dto.BookmarkLivenessConfigValue
import top.tcyeee.bookmarkify.entity.enums.PingOutcome
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 校验巡检熔断的判据。
 *
 * 值得测的是**误触发与漏触发的边界**：熔断太灵敏会让巡检永远跑不完（一批候选里混几个死站点
 * 就中止），太钝则挡不住真正要防的事故——抓取服务故障时把整批健康书签写成失联。
 */
class LivenessPolicyTest {

    private fun outcomes(alive: Int = 0, dead: Int = 0, unknown: Int = 0): List<PingOutcome> =
        List(alive) { PingOutcome.ALIVE } + List(dead) { PingOutcome.DEAD } + List(unknown) { PingOutcome.UNKNOWN }

    // ────── 由探测事实判定死活 ──────

    private fun outcomeOf(status: Int) =
        LivenessPolicy.outcomeOf(reachable = true, status = status, blocked = false)

    @Test
    fun `2xx 与 3xx 判活`() {
        listOf(200, 201, 204, 301, 302, 304, 307).forEach {
            assertEquals(PingOutcome.ALIVE, outcomeOf(it), "status=$it")
        }
    }

    @Test
    fun `页面确实没了才判死`() {
        // 这是本次改造的全部意义：改造前 scrapper 折叠成 alive=true，深链失效永远发现不了
        assertEquals(PingOutcome.DEAD, outcomeOf(404))
        assertEquals(PingOutcome.DEAD, outcomeOf(410))
    }

    @Test
    fun `反爬状态码判无结论而不是判死`() {
        // 机房出口 IP 被整段拒绝，而同一个 URL 在用户浏览器里完全正常 ——
        // 判死等于拿我方的网络位置给用户的书签定罪
        listOf(403, 406, 412, 425, 429, 451).forEach {
            assertEquals(PingOutcome.UNKNOWN, outcomeOf(it), "status=$it")
        }
    }

    @Test
    fun `要登录或不认这个方法都说明页面还在`() {
        assertEquals(PingOutcome.ALIVE, outcomeOf(401))
        assertEquals(PingOutcome.ALIVE, outcomeOf(405))
    }

    @Test
    fun `5xx 判死`() {
        listOf(500, 502, 503, 504).forEach {
            assertEquals(PingOutcome.DEAD, outcomeOf(it), "status=$it")
        }
    }

    @Test
    fun `传输层失败判死`() {
        assertEquals(
            PingOutcome.DEAD,
            LivenessPolicy.outcomeOf(reachable = false, status = null, blocked = false)
        )
    }

    @Test
    fun `我方拒绝去探绝不能判死`() {
        // 与 classifyScrapperError 里 E308 不并进 E304 是同一条界线：
        // 「我们拒绝抓它」不是「它挂了」的证据
        assertEquals(
            PingOutcome.UNKNOWN,
            LivenessPolicy.outcomeOf(reachable = false, status = null, blocked = true)
        )
        // 即便同时带着状态码，blocked 也优先
        assertEquals(
            PingOutcome.UNKNOWN,
            LivenessPolicy.outcomeOf(reachable = true, status = 200, blocked = true)
        )
    }

    @Test
    fun `拿到响应却没有状态码属于契约问题判无结论`() {
        assertEquals(
            PingOutcome.UNKNOWN,
            LivenessPolicy.outcomeOf(reachable = true, status = null, blocked = false)
        )
    }

    @Test
    fun `全部存活不熔断`() {
        assertNull(LivenessPolicy.breakerReason(outcomes(alive = 200)))
    }

    @Test
    fun `样本不足时即使全是无结论也不熔断`() {
        // 一轮只剩几条候选、恰好抓取服务抖了一下，不该因此判定系统性故障
        assertNull(LivenessPolicy.breakerReason(outcomes(unknown = 9)))
    }

    @Test
    fun `无结论过半即熔断`() {
        assertNotNull(LivenessPolicy.breakerReason(outcomes(alive = 5, unknown = 5)))
    }

    @Test
    fun `无结论未过半不熔断`() {
        assertNull(LivenessPolicy.breakerReason(outcomes(alive = 6, unknown = 4)))
    }

    @Test
    fun `少量死站点混在健康批次里不熔断`() {
        // 候选本来就是「久未检查」的记录，混进死站点是正常现象，这正是巡检要发现的东西
        assertNull(LivenessPolicy.breakerReason(outcomes(alive = 150, dead = 50)))
    }

    @Test
    fun `接近全灭时熔断——这种形态只可能是我方出网链路故障`() {
        // scrapper 活着、响应全是合法 2xx，仅 alive=false，UNKNOWN 判据完全看不见
        assertNotNull(LivenessPolicy.breakerReason(outcomes(alive = 10, dead = 190)))
    }

    @Test
    fun `小样本全灭不熔断——避免把真实的小批死链判成故障`() {
        assertNull(LivenessPolicy.breakerReason(outcomes(dead = 19)))
    }

    @Test
    fun `空批次不熔断`() {
        assertNull(LivenessPolicy.breakerReason(emptyList()))
    }

    // ────── 退避曲线 ──────

    private val now: LocalDateTime = LocalDateTime.of(2026, 7, 30, 12, 0)

    private fun nextCheck(
        outcome: PingOutcome,
        consecutiveFail: Int,
        config: BookmarkLivenessConfigValue = BookmarkLivenessConfigValue(),
    ) = LivenessPolicy.nextCheckAt(
        now = now,
        outcome = outcome,
        consecutiveFail = consecutiveFail,
        config = config,
    )

    @Test
    fun `存活按正常周期复查`() {
        assertEquals(now.plusHours(168), nextCheck(PingOutcome.ALIVE, 0))
    }

    @Test
    fun `无结论只做短退避——不能让我方故障把全表推到曲线末端`() {
        // 关键点：UNKNOWN 的间隔与失败次数无关，且远短于任何失败退避
        assertEquals(now.plusHours(1), nextCheck(PingOutcome.UNKNOWN, 0))
        assertEquals(now.plusHours(1), nextCheck(PingOutcome.UNKNOWN, 7))
    }

    @Test
    fun `失败按指数退避`() {
        assertEquals(now.plusHours(24), nextCheck(PingOutcome.DEAD, 1))
        assertEquals(now.plusHours(48), nextCheck(PingOutcome.DEAD, 2))
        assertEquals(now.plusHours(96), nextCheck(PingOutcome.DEAD, 3))
        assertEquals(now.plusHours(192), nextCheck(PingOutcome.DEAD, 4))
    }

    @Test
    fun `退避有上限，不会无限翻倍`() {
        val capped = now.plusHours(384)
        assertEquals(capped, nextCheck(PingOutcome.DEAD, 5))
        assertEquals(capped, nextCheck(PingOutcome.DEAD, 9))
        // 越界的失败次数也不该算出个荒谬的时间点（哪怕归档已经先一步把它移出候选池）
        assertEquals(capped, nextCheck(PingOutcome.DEAD, 999))
    }

    @Test
    fun `失败次数异常为0时退化为基础间隔而不是崩掉`() {
        // 理论上 DEAD 一定伴随 fail>=1，但这里不该依赖调用方的纪律
        assertEquals(now.plusHours(24), nextCheck(PingOutcome.DEAD, 0))
    }

    @Test
    fun `叠加倍数可配：取 3 时按三倍增长`() {
        val config = BookmarkLivenessConfigValue(
            abnormalCheckIntervalHours = 2,
            abnormalBackoffMultiplier = 3,
            abnormalMaxIntervalHours = 1000,
        )
        assertEquals(now.plusHours(2), nextCheck(PingOutcome.DEAD, 1, config))
        assertEquals(now.plusHours(6), nextCheck(PingOutcome.DEAD, 2, config))
        assertEquals(now.plusHours(18), nextCheck(PingOutcome.DEAD, 3, config))
        assertEquals(now.plusHours(54), nextCheck(PingOutcome.DEAD, 4, config))
    }

    @Test
    fun `叠加倍数取 1 即固定间隔，不退避`() {
        val config = BookmarkLivenessConfigValue(
            abnormalCheckIntervalHours = 6,
            abnormalBackoffMultiplier = 1,
            abnormalMaxIntervalHours = 384,
        )
        listOf(1, 2, 5, 9).forEach {
            assertEquals(now.plusHours(6), nextCheck(PingOutcome.DEAD, it, config), "fail=$it")
        }
    }

    @Test
    fun `最长间隔封顶，且不因失败次数极大而溢出`() {
        val config = BookmarkLivenessConfigValue(
            abnormalCheckIntervalHours = 24,
            abnormalBackoffMultiplier = 4,
            abnormalMaxIntervalHours = 100,
        )
        assertEquals(now.plusHours(24), nextCheck(PingOutcome.DEAD, 1, config))
        assertEquals(now.plusHours(96), nextCheck(PingOutcome.DEAD, 2, config))
        // 第三次本该 384，被上限截到 100
        assertEquals(now.plusHours(100), nextCheck(PingOutcome.DEAD, 3, config))
        // 这条是真正的回归点：幂运算写法在这里会溢出成负数，算出一个过去的时间点，
        // 那条记录会永久占据候选队列的队头把后面的全部饿死
        assertEquals(now.plusHours(100), nextCheck(PingOutcome.DEAD, Int.MAX_VALUE, config))
    }

    @Test
    fun `倍数为 1 时失败次数极大也立刻返回，不在巡检线程上空转`() {
        val config = BookmarkLivenessConfigValue(
            abnormalCheckIntervalHours = 6,
            abnormalBackoffMultiplier = 1,
            abnormalMaxIntervalHours = 384,
        )
        // 平坦曲线碰不到「触顶即返回」那道出口，没有这条快速返回就会空转 20 亿次
        assertEquals(now.plusHours(6), nextCheck(PingOutcome.DEAD, Int.MAX_VALUE, config))
    }

    @Test
    fun `上限配得比基数还小时退化为基数，而不是缩短到每小时重探`() {
        val config = BookmarkLivenessConfigValue(
            abnormalCheckIntervalHours = 24,
            abnormalMaxIntervalHours = 1,
        )
        assertEquals(now.plusHours(24), nextCheck(PingOutcome.DEAD, 1, config))
        assertEquals(now.plusHours(24), nextCheck(PingOutcome.DEAD, 5, config))
    }

    // ────── 判死的确认门槛 ──────

    @Test
    fun `连续三次失败才判失活`() {
        assertFalse(LivenessPolicy.confirmsDead(consecutiveFail = 1, required = 3))
        assertFalse(LivenessPolicy.confirmsDead(consecutiveFail = 2, required = 3))
        assertTrue(LivenessPolicy.confirmsDead(consecutiveFail = 3, required = 3))
        assertTrue(LivenessPolicy.confirmsDead(consecutiveFail = 7, required = 3))
    }

    @Test
    fun `门槛为 0 或负数时退回单次判死，而不是永远判不了死`() {
        listOf(0, -1).forEach {
            assertTrue(LivenessPolicy.confirmsDead(consecutiveFail = 1, required = it), "required=$it")
        }
        // 还没有任何一次失败时仍然不判死
        assertFalse(LivenessPolicy.confirmsDead(consecutiveFail = 0, required = 0))
    }

    @Test
    fun `默认门槛必须小于归档阈值，否则失活状态永不出现`() {
        // 归档先于判失活发生的话，「失活书签重试巡检」按 UNREACHABLE 选候选就永远选不到东西
        val default = BookmarkLivenessConfigValue()
        assertTrue(default.deadConfirmFailures < default.maxRetryFailures)
    }

    @Test
    fun `归档阈值来自配置`() {
        assertFalse(LivenessPolicy.shouldArchive(consecutiveFail = 9, maxRetryFailures = 10))
        assertTrue(LivenessPolicy.shouldArchive(consecutiveFail = 10, maxRetryFailures = 10))
        // 管理员调小之后立刻生效，不需要发版
        assertTrue(LivenessPolicy.shouldArchive(consecutiveFail = 3, maxRetryFailures = 3))
        assertFalse(LivenessPolicy.shouldArchive(consecutiveFail = 2, maxRetryFailures = 3))
    }

    @Test
    fun `配置坏掉时归档阈值取下限，而不是把整张表一轮归档`() {
        // 归档没有自动撤销路径（唯一出口是有人重新添加该网址），所以一个 0 / 负数
        // 绝不能被当真——那会让本轮每一条探测失败的记录当场归档
        listOf(0, -1, 1).forEach { bad ->
            assertFalse(
                LivenessPolicy.shouldArchive(consecutiveFail = 1, maxRetryFailures = bad),
                "maxRetryFailures=$bad 时单次失败不该归档",
            )
        }
        assertTrue(LivenessPolicy.shouldArchive(consecutiveFail = 2, maxRetryFailures = 0))
    }

    // ────── 域名级活性：防的是"局部证据推出全局结论" ──────

    private val D = PingOutcome.DEAD
    private val A = PingOutcome.ALIVE
    private val U = PingOutcome.UNKNOWN

    /**
     * 这条是整个站点层短路机制的安全底线。
     *
     * 用户收藏的大多是深链，而深链失效（视频被删、仓库归档）与域名死活无关。若一个被删的视频
     * 就能把 youtube.com 判死，下一轮该域名下所有页面都会被短路成失联、不再实际探测 ——
     * 一次误判级联成整站误判，且再没有探测能纠正它。
     */
    @Test
    fun `页面全挂但根地址通着时绝不判定域名死亡`() {
        assertEquals(
            LivenessPolicy.SiteVerdict.UNCHANGED,
            LivenessPolicy.siteVerdict(listOf(D, D, D), rootOutcome = A),
            "根地址通着说明域名健在，那些页面是真的没了",
        )
    }

    @Test
    fun `页面全挂且根地址也挂了才判定域名死亡`() {
        assertEquals(
            LivenessPolicy.SiteVerdict.DEAD,
            LivenessPolicy.siteVerdict(listOf(D, D), rootOutcome = D),
        )
    }

    @Test
    fun `没探根地址时不足以判死`() {
        assertEquals(
            LivenessPolicy.SiteVerdict.UNCHANGED,
            LivenessPolicy.siteVerdict(listOf(D, D), rootOutcome = null),
        )
    }

    /** UNKNOWN 是我方链路的问题，混进来就不算"全部失联"，更不能拿它判死 */
    @Test
    fun `掺了无结论就不算全部失联`() {
        assertEquals(
            LivenessPolicy.SiteVerdict.UNCHANGED,
            LivenessPolicy.siteVerdict(listOf(D, U), rootOutcome = D),
        )
        assertEquals(
            LivenessPolicy.SiteVerdict.UNCHANGED,
            LivenessPolicy.siteVerdict(listOf(U, U), rootOutcome = D),
        )
    }

    @Test
    fun `根地址无结论时保持原状`() {
        assertEquals(
            LivenessPolicy.SiteVerdict.UNCHANGED,
            LivenessPolicy.siteVerdict(listOf(D, D), rootOutcome = U),
        )
    }

    /** 判活不需要根地址确认：任意一个页面通了，域名必然活着 */
    @Test
    fun `任意页面存活即判定域名存活且无需根地址`() {
        assertEquals(
            LivenessPolicy.SiteVerdict.ALIVE,
            LivenessPolicy.siteVerdict(listOf(D, A, D), rootOutcome = null),
        )
        // 即使根地址探测失败（首页可能被防火墙挡了），有页面通就说明域名是活的
        assertEquals(
            LivenessPolicy.SiteVerdict.ALIVE,
            LivenessPolicy.siteVerdict(listOf(A), rootOutcome = D),
        )
    }

    @Test
    fun `一个页面都没探到时无从判断`() {
        assertEquals(
            LivenessPolicy.SiteVerdict.UNCHANGED,
            LivenessPolicy.siteVerdict(emptyList(), rootOutcome = D),
            "本轮没有该域名的探测样本，不该凭根地址一次结果就改动它",
        )
    }

    /** 单个页面失联 + 根地址失联：样本虽小，但根地址已经是域名级的直接证据 */
    @Test
    fun `单个页面配合根地址确认也可判死`() {
        assertEquals(
            LivenessPolicy.SiteVerdict.DEAD,
            LivenessPolicy.siteVerdict(listOf(D), rootOutcome = D),
        )
    }
}
