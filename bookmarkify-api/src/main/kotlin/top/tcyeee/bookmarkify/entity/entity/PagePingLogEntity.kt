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
