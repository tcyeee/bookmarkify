package top.tcyeee.bookmarkify.config.monitor

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 覆盖 [ConsecutiveErrorTracker] 的三条不变量，都没有症状、只能靠测试盯着：
 * - 没攒够阈值不报警；
 * - 报警之后进入冷却期，同一场故障不会刷屏；
 * - 冷却期一过、故障仍在继续，不需要重新攒够阈值就能补报——但两次报错之间隔太久
 *   （超过窗口）必须被当成新的一轮，不能让旧账和新账拼在一起凑数。
 */
class ConsecutiveErrorTrackerTest {

    private class FakeClock(var now: Long = 0L) {
        fun advance(millis: Long) {
            now += millis
        }
    }

    private fun tracker(clock: FakeClock, threshold: Int = 3, windowMillis: Long = 5 * 60_000L, cooldownMillis: Long = 30 * 60_000L) =
        ConsecutiveErrorTracker(threshold, windowMillis, cooldownMillis, clock = { clock.now })

    @Test
    fun `未攒够阈值不报警`() {
        val clock = FakeClock()
        val t = tracker(clock)
        assertFalse(t.onError())
        clock.advance(1_000)
        assertFalse(t.onError())
    }

    @Test
    fun `第三条错误攒够阈值触发报警`() {
        val clock = FakeClock()
        val t = tracker(clock)
        assertFalse(t.onError())
        clock.advance(1_000)
        assertFalse(t.onError())
        clock.advance(1_000)
        assertTrue(t.onError())
    }

    @Test
    fun `冷却期内即便继续报错也不重复推送`() {
        val clock = FakeClock()
        val t = tracker(clock)
        repeat(2) { t.onError(); clock.advance(1_000) }
        assertTrue(t.onError()) // 第 3 条命中，触发一次

        clock.advance(1_000)
        assertFalse(t.onError()) // 冷却期(30min)内，即便还在错也不再推送
        clock.advance(1_000)
        assertFalse(t.onError())
    }

    @Test
    fun `冷却期一过故障仍在继续则立刻补报一次`() {
        val clock = FakeClock()
        val t = tracker(clock)
        repeat(2) { t.onError(); clock.advance(1_000) }
        assertTrue(t.onError()) // 第一次报警

        // 冷却期(30min)内故障持续发生：每隔 4 分钟一条，小于 5 分钟窗口所以计数不会被重置，
        // 但因为还在冷却期内，一直不重复推送
        var alerted = false
        repeat(7) { // 7 * 4min = 28min，仍在 30min 冷却期内
            clock.advance(4 * 60_000L)
            alerted = t.onError()
        }
        assertFalse(alerted)

        // 再来一条：此时距上次报警已超过冷却期，且中途从未断档超过 5 分钟窗口，
        // 计数一直保持在阈值以上——不需要重新攒够 3 条，立刻补报
        clock.advance(4 * 60_000L)
        assertTrue(t.onError())
    }

    @Test
    fun `两次报错间隔超过窗口视为新一轮突发`() {
        val clock = FakeClock()
        val t = tracker(clock)
        assertFalse(t.onError())
        clock.advance(1_000)
        assertFalse(t.onError())

        // 间隔超过窗口(5min)：前两条作废，这一条只是新一轮的第 1 条，不该凑成第 3 条触发报警
        clock.advance(6 * 60_000L)
        assertFalse(t.onError())
    }
}
