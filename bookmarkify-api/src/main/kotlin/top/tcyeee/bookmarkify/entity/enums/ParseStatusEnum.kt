package top.tcyeee.bookmarkify.entity.enums

/**
 * @author tcyeee
 * @date 1/7/26 22:41
 */
enum class ParseStatusEnum {
    // 尚未处理(新建/排队等待抓取任务执行)
    PENDING,
    // 抓取成功,获取到可用内容(是否疑似反爬见 PageEntity.antiCrawlerBlocked)
    SUCCESS,
    // 抓取失败(ping不通/请求异常/HTTP错误等各种原因的统称,可能是暂时性故障)
    UNREACHABLE,

    /**
     * 长期失联，已停止巡检。
     *
     * 连续探测失败达到 [LivenessPolicy.ARCHIVE_AFTER_FAILURES][top.tcyeee.bookmarkify.server.liveness.LivenessPolicy.ARCHIVE_AFTER_FAILURES]
     * 次（按退避曲线累计已两个多月）后由巡检任务写入。与 [UNREACHABLE] 的区别只在调度：
     * 两个巡检任务分别只认 UNREACHABLE 与 SUCCESS，所以归档记录会自然退出候选池，
     * 不再每半个月去 ping 一个早就没了的域名，也就不会挤占 LIMIT 名额。
     *
     * 对用户侧没有新语义：`isActivity` 依旧是 false，照旧算失效书签。
     *
     * **不是不可逆终态。** 除了管理员手动刷新/检测，`reviveArchivedBookmarks`（每天一轮）
     * 会捞归档记录做复活探测，ping 通即重新抓取、写回 SUCCESS。这条出口是必须的：把记录
     * 送进归档的是一条可能出错的自动判定链（域名临时改 DNS、机房出口被目标站点拉黑一段
     * 时间都够了），只有自动入口而没有自动出口，一次误判就等于永久删除。
     */
    ARCHIVED
}