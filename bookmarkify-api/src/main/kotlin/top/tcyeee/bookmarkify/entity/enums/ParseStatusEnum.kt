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
     * 连续探测失败达到管理员配置的
     * [maxRetryFailures][top.tcyeee.bookmarkify.entity.dto.BookmarkLivenessConfigValue.maxRetryFailures]
     * （默认 10，按退避曲线累计已两个多月）后由巡检任务写入。与 [UNREACHABLE] 的区别只在调度：
     * 三个巡检任务分别只认 UNREACHABLE / SUCCESS / PENDING，所以归档记录会自然退出候选池，
     * 不再每半个月去 ping 一个早就没了的域名，也就不会挤占 LIMIT 名额。
     *
     * 对用户侧没有新语义：`isActivity` 依旧是 false，照旧算失效书签。
     *
     * **是终态，但不是死路。** 没有任何定时任务会再选中它——出口是**按需**的：管理员手动
     * 刷新/检测，或者有用户来添加这个网址（`BookmarkServiceImpl.reviveOnAdd` 就地把
     * `consecutiveFail` 清零、状态改回 PENDING，走完整解析链路）。出口必须存在，因为把记录
     * 送进归档的是一条可能出错的自动判定链（域名临时改 DNS、机房出口被目标站点拉黑一段时间
     * 都够了），只有自动入口而没有出口，一次误判就等于永久删除；但出口不必是定时的——
     * 「现在有人正要收藏它」比「过了 30 天」是强得多的复活信号，且不花空转成本。
     */
    ARCHIVED
}