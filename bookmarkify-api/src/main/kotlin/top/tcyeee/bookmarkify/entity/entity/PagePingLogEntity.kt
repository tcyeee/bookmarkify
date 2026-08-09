package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import top.tcyeee.bookmarkify.entity.enums.PingOutcome
import java.time.LocalDateTime

@TableName("page_ping_log")
data class PagePingLogEntity(
    @TableId val id: String = IdUtil.fastUUID(),
    val pageId: String,
    val urlHost: String,
    /** 探测结论。[alive] 只是它的派生视图，判断一律以本列为准。 */
    val outcome: PingOutcome,
    /**
     * 是否存活。[PingOutcome.UNKNOWN] 时为 null——「没探到」不是「没存活」，
     * 把它记成 false 正是本次要修的那个错误在日志表里的翻版。
     */
    val alive: Boolean? = outcome.toAliveOrNull(),
    /** ping 通后是否触发了重新解析 */
    val triggeredParse: Boolean = false,
    /**
     * 产生这次探测的巡检轮次（`sweep_log.id`）。
     *
     * 这一列是后台「点开一轮巡检 → 看它探了哪些页面」唯一的依据。不用时间窗代替：
     * 两个巡检任务各自加锁、彼此不互斥，锁 TTL 30 分钟，一轮跑久了窗口就会重叠，
     * 而本表没有 task_label，重叠时无从拆分。
     *
     * null = 2026-08-09 之前的历史行，或非巡检路径发起的探测。
     */
    val sweepId: String? = null,
    val createTime: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        private fun PingOutcome.toAliveOrNull(): Boolean? = when (this) {
            PingOutcome.ALIVE -> true
            PingOutcome.DEAD -> false
            PingOutcome.UNKNOWN -> null
        }
    }
}
