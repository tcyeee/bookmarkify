package top.tcyeee.bookmarkify.config.monitor

/**
 * 「连续报错」的纯判断逻辑，不依赖 Logback，可直接单测。
 *
 * 单实例部署（见根 CLAUDE.md「API 运行为单实例」段），进程内计数即可，不需要 Redis——
 * 与 [top.tcyeee.bookmarkify.server.config.JsonConfigAccessor] 的进程内缓存是同一个前提。
 *
 * @param threshold 一轮突发内累计多少次才判定为「连续报错」
 * @param windowMillis 两次报错间隔超过这个值，则不算同一轮突发，计数重置为 1——
 *   日志本身不携带「已恢复」信号，滑动窗口是能拿到的最接近的近似
 * @param cooldownMillis 报警之后，至少要等这么久才允许下一次报警，避免同一场故障反复刷屏
 */
class ConsecutiveErrorTracker(
    private val threshold: Int,
    private val windowMillis: Long,
    private val cooldownMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var count = 0
    private var lastErrorAt = 0L

    // null = 从未报过警。不能用 0 当"从未报警"的哨兵值——冷却判断是"now - lastAlertAt < cooldown"，
    // 生产环境里 now 是真实 epoch 毫秒(远大于 cooldown)所以用 0 凑巧也对，但那是走运,不是设计,
    // 单测把 clock 从 0 起跳时就会假阳性地把第一次报警也判进冷却期。
    private var lastAlertAt: Long? = null

    /**
     * 记一次 ERROR 级别日志；返回 true 表示这一次应当触发一次推送。
     *
     * 触发后计数清零，但 [lastAlertAt] 不清——冷却期内故障若仍在持续，计数会重新攒够
     * [threshold]，此时不再等待冷却期外的第一条错误立刻补报一次即可，不需要重新攒够阈值：
     * 「冷却期已过 + 故障仍在发生」本身就已经是应该马上再报一次的信号。
     */
    @Synchronized
    fun onError(): Boolean {
        val now = clock()
        count = if (now - lastErrorAt > windowMillis) 1 else count + 1
        lastErrorAt = now
        if (count < threshold) return false
        val sinceLastAlert = lastAlertAt?.let { now - it }
        if (sinceLastAlert != null && sinceLastAlert < cooldownMillis) return false
        count = 0
        lastAlertAt = now
        return true
    }
}
