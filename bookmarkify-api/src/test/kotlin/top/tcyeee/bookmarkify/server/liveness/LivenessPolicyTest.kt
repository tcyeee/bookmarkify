package top.tcyeee.bookmarkify.server.liveness

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

    private fun nextCheck(outcome: PingOutcome, consecutiveFail: Int) = LivenessPolicy.nextCheckAt(
        now = now,
        outcome = outcome,
        consecutiveFail = consecutiveFail,
        activeIntervalHours = 168,
        abnormalIntervalHours = 24,
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
    fun `归档阈值`() {
        assertFalse(LivenessPolicy.shouldArchive(LivenessPolicy.ARCHIVE_AFTER_FAILURES - 1))
        assertTrue(LivenessPolicy.shouldArchive(LivenessPolicy.ARCHIVE_AFTER_FAILURES))
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
