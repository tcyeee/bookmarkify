package top.tcyeee.bookmarkify.entity.enums

/**
 * @author tcyeee
 * @date 1/7/26 22:41
 */
enum class ParseStatusEnum {
    // 尚未处理(新建/排队等待抓取任务执行)
    PENDING,
    // 抓取成功,获取到可用内容(是否疑似反爬见 BookmarkEntity.antiCrawlerBlocked)
    SUCCESS,
    // 抓取失败(ping不通/请求异常/HTTP错误等各种原因的统称,可能是暂时性故障)
    UNREACHABLE
}