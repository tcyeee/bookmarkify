package top.tcyeee.bookmarkify.entity.dto

/** 存入 system_config 的书签巡检配置 */
data class BookmarkLivenessConfigValue(
    /** 已激活书签的活性探测间隔(小时) */
    val activeCheckIntervalHours: Int = 168,
    /** 异常书签的**初次**重试间隔(小时)，同时是指数退避的基数 */
    val abnormalCheckIntervalHours: Int = 24,
    /**
     * 重试间隔的叠加倍数：每多失败一次，间隔在上一次的基础上乘以这个数。
     *
     * 取 1 即为固定间隔（不退避）。此前这个值硬编码为 2，指数上限也硬编码为 4 档，
     * 于是「多久放弃重试一个死站点」这条曲线在线上完全不可调——而它恰恰是要按实际
     * 数据量和抓取预算来回调的那种参数。
     */
    val abnormalBackoffMultiplier: Int = 2,
    /**
     * 重试间隔的上限(小时)。默认 384h = 16 天，正是原先「24h 基数 × 2^4」算出来的那个封顶值。
     *
     * 有上限才有意义：没有它，倍数一路乘下去会算出几年后的时间点，而
     * [top.tcyeee.bookmarkify.server.liveness.LivenessPolicy.ARCHIVE_AFTER_FAILURES]
     * 早就该把这条记录移出候选池了，多出来的那部分退避纯属无效。
     */
    val abnormalMaxIntervalHours: Int = 384,
    /**
     * 连续多少次探测都不通过，才把书签判成失活。
     *
     * 一次 HEAD 探测失败并不等于站点没了：目标站点重启一次、我方出口抖一下、CDN 换一次节点
     * 都足以让单次探测失败，而用户看到的是好端端的书签突然变灰。要求连续 N 次才收口，
     * 把「瞬时故障」和「真的没了」区分开——代价只是判死晚几个退避周期，而那几个周期里
     * 书签对用户仍然显示为可用，本来也没有损失。
     *
     * 注意 [PingOutcome.UNKNOWN][top.tcyeee.bookmarkify.entity.enums.PingOutcome.UNKNOWN]
     * 不计入这个计数（那是我方链路的问题），所以 N 次指的是 N 次**站点的**失败。
     */
    val deadConfirmFailures: Int = 3,
    /**
     * 内容重新抓取的间隔(天)。
     *
     * 「站点还活着吗」与「内容变了吗」是两件事，频率也该不同：活性探测只发一个 HEAD 请求，很便宜；
     * 重新抓取要走完整链路（可能拉起无头浏览器、下载图片、上传 OSS），贵得多。默认 30 天——
     * 站点改版、换 logo 的节奏本来就是月级的。
     */
    val contentRefreshIntervalDays: Int = 30,
    /**
     * 失活网站的最大重试次数：连续失败累计到这个数，记录转入
     * [ARCHIVED][top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum.ARCHIVED]，
     * **此后不再有任何定时任务碰它**。
     *
     * 默认 10 ——按 24h 起步的退避曲线算，到这一步累计已经探测了两个多月。
     *
     * ## 为什么这个数必须可配
     *
     * 它决定的是「多久之后放弃一个站点」，而这条线该画在哪里完全取决于数据量和抓取预算：
     * 几百条书签时多探几轮无所谓，几十万条时候选池最前面全是尸体，配合 `LIMIT` 会把真正
     * 该复查的记录挤掉。此前它是 `LivenessPolicy` 里的一个 `const val`，改一次要重新发版。
     *
     * ## 到达之后为什么是真的停下
     *
     * 归档过去不是终态：`reviveArchivedBookmarks` 每 30 天还会把它捞起来 ping 一次，理由是
     * 「自动的入口必须配一个自动的出口」。但那个出口的成本是永久的（一个再也不会回来的域名，
     * 每 30 天照样吃一次探测和一个 `LIMIT` 名额），收益却近乎为零。现在换成一个**按需**的出口：
     * 有新用户添加这个网址，就地重置计数并重新检查（见 `BookmarkServiceImpl.reviveOnAdd`）。
     * 「有人正要收藏它」比「过了 30 天」是强得多的复活信号，且不花任何空转成本。
     */
    val maxRetryFailures: Int = 10,
)
