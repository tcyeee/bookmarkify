package top.tcyeee.bookmarkify.entity.dto

/** 存入 system_config 的书签巡检配置 */
data class BookmarkLivenessConfigValue(
    /** 已激活书签的活性探测间隔(小时) */
    val activeCheckIntervalHours: Int = 168,
    /** 异常书签的重试间隔(小时)，同时是指数退避的基数 */
    val abnormalCheckIntervalHours: Int = 24,
    /**
     * 内容重新抓取的间隔(天)。
     *
     * 「站点还活着吗」与「内容变了吗」是两件事，频率也该不同：活性探测只发一个 HEAD 请求，很便宜；
     * 重新抓取要走完整链路（可能拉起无头浏览器、下载图片、上传 OSS），贵得多。默认 30 天——
     * 站点改版、换 logo 的节奏本来就是月级的。
     */
    val contentRefreshIntervalDays: Int = 30,
)
