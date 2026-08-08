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
    /**
     * 哪个巡检任务（retryUnreachableBookmarks / livenessCheckStaleBookmarks）。
     *
     * 历史数据里还会出现 `reviveArchivedBookmarks`——那条每天一轮的归档复活探测已于
     * 2026-08-07 移除（归档改为终态，出口换成「有用户重新添加该网址」）。旧行仍要能正常展示，
     * 所以后台的标签映射保留了它。
     */
    val taskLabel: String,
    /** 本轮实际处理的候选数（已按 LIMIT 截断、已过滤掉非域名类型） */
    val candidates: Int,
    /**
     * 到期候选的总数，**不含** LIMIT，也**不含**非域名类型的过滤。
     *
     * 判断「检测间隔配置追不上数据量」要拿它和 [batchSize] 比，**不能**和 [candidates] 比：
     * 后者还额外扣掉了本地地址/IP 这类压根不该探测的记录，于是只要本轮到期的记录里混进一条
     * 这样的书签，`backlog > candidates` 就恒成立 —— 后台曾据此常亮橙色告警。
     */
    val backlog: Long,
    /**
     * 本轮的单次处理上限（`LIMIT`），即 `backlog` 该拿来对比的那个阈值。
     *
     * 落库而不是在后台里写死：两个巡检任务的上限不同（50 / 200），且它以后会变成配置项，
     * 而一条历史轮次记录该按**当时**的上限来判读。null 是 2026-08-08 之前的历史行。
     */
    val batchSize: Int? = null,
    /** 真正发起了探测的条数 */
    val probed: Int,
    /** 被站点层短路、直接复用上一轮站点结论的条数 */
    val shortCircuited: Int,
    /**
     * 短路的那部分里结论为 DEAD 的条数；其余是 UNKNOWN（根地址探测无结论），不会有 ALIVE
     * ——根地址通了的域名会回到逐页探测的路径，计入 [probed]。
     *
     * 存在的理由：[deadCount] / [unknownCount] 统计的是「本轮探测 + 短路复用」的合计，而
     * `breakerReason` 的分母只有真正探测过的那部分。没有这个数，后台既没法还原熔断看到的比例，
     * 也没法告诉运维「失联 180」里有多少只是上一轮站点结论的复用。null 是历史行。
     */
    val shortCircuitedDead: Int? = null,
    val aliveCount: Int,
    /** 判定失联的条数，**含**站点层短路复用的结论；其中短路的部分见 [shortCircuitedDead] */
    val deadCount: Int,
    /** 无结论的条数，**含**站点层短路复用的结论 */
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
