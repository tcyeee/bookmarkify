package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import java.time.LocalDateTime

/**
 * 一轮活性巡检一行。
 *
 * ## 为什么需要这张表
 *
 * `bookmark_ping_log` 记的是**单次探测**，回答不了"这一轮整体怎么样"：一轮是不是被熔断
 * 中止了、积压有没有追上、有多少条因为解析队列拥堵被推迟。而这些恰恰是判断巡检系统
 * 本身是否健康的全部依据。
 *
 * 尤其是熔断。它的语义是"我方链路坏了，本轮全表结论不可信"——这是整个系统里最该被立刻
 * 知道的信号，而它此前唯一的出口是一行 `log.error`。日志会滚动、没人盯着，等于没有。
 * 落成一行数据之后，"最近一天熔断过几次"变成一句 SQL，也才谈得上告警。
 *
 * 这与 `stuckLoadingStats` 之于添加链路是同一个位置：一条真正的 SLI。
 */
@TableName("sweep_log")
data class SweepLogEntity(
    @TableId val id: String = IdUtil.fastUUID(),
    /** 哪个巡检任务（retryUnreachableBookmarks / livenessCheckStaleBookmarks / reviveArchivedBookmarks） */
    val taskLabel: String,
    /** 本轮实际处理的候选数（已按 LIMIT 截断、已过滤掉非域名类型） */
    val candidates: Int,
    /** 到期候选的总数，**不含** LIMIT。持续大于 candidates 说明检测间隔配置已经追不上数据量 */
    val backlog: Long,
    /** 真正发起了探测的条数 */
    val probed: Int,
    /** 被站点层短路、直接复用上一轮站点结论的条数 */
    val shortCircuited: Int,
    val aliveCount: Int,
    val deadCount: Int,
    val unknownCount: Int,
    /** 触发了重新抓取的条数 */
    val triggeredParse: Int,
    /** 想重新抓取、但因解析队列余量不足被推迟到下一轮的条数 */
    val deferredParse: Int,
    /**
     * 熔断原因；null 表示本轮正常完成。
     *
     * 非 null 意味着本轮**没有改动任何书签**——探测结果整体呈系统性失败的形态，
     * 判据见 `LivenessPolicy.breakerReason`。
     */
    val breakerReason: String? = null,
    val durationMs: Long,
    val createTime: LocalDateTime = LocalDateTime.now(),
)
