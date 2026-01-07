package top.tcyeee.bookmarkify.entity.enums

/**
 * @author tcyeee
 * @date 1/7/26 22:41
 */
enum class ParseStatusEnum {
    // 未开始
    LOADING,
    // 解析成功
    SUCCESS,
    // 网站已关闭
    CLOSED,
    // 被阻止访问（如机器人、反爬虫或者黑名单等）
    BLOCKED
}