package top.tcyeee.bookmarkify.config.monitor

import org.slf4j.Marker
import org.slf4j.MarkerFactory

/**
 * 日志标记。目前只有一个：**"这条 WARN 是我方基础设施坏了"**。
 *
 * ## 为什么不是直接打 ERROR
 *
 * [ServerChanAlertAppender] 只看 ERROR，而本项目真正最该告警的几条路径刻意打的是 WARN：
 * `E307`（抓取服务不可用）、`E306`（埋点服务不可达）、`pingWebsite` 的"抓取服务不可达"。
 * 它们打 warn 是对的 —— 现场更完整的记录在 `scrapper_call_log` 里，而且这些路径的调用量大，
 * 整条打成 ERROR 会把真正的未预期异常冲淡。
 *
 * 但代价是：**scrapper 挂掉一条告警都不会发**，而 E307 的已知后果正是"用户桌面上的书签
 * 转圈 30 分钟"（`parseByApi` 刻意把它留在 PENDING，等 `drainStuckLoading` 的陈旧阈值）。
 * 于是最该被人知道的故障，恰好落在告警看不见的那一级上。
 *
 * [INFRA] 解开这个结：级别仍是 WARN（文本日志里的噪音权重不变），但 appender 认这个标记，
 * 把它与 ERROR 一同计入"连续报错"。判据从"级别"换成"这是谁的错"，而后者才是告警真正想问的。
 */
object LogMarkers {

    /** 我方基础设施/依赖服务不可用。打在 WARN 上也会参与告警计数 */
    val INFRA: Marker = MarkerFactory.getMarker("INFRA")

    /** 这条事件是否带了 [INFRA] 标记（appender 侧判定用） */
    fun isInfra(markers: List<Marker>?): Boolean =
        markers?.any { it.contains(INFRA) } == true
}
