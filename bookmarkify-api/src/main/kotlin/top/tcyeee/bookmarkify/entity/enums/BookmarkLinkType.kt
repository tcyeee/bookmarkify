package top.tcyeee.bookmarkify.entity.enums

/**
 * 书签链接类型：按 host 对 bookmark_user_link 进行分类，决定是否需要抓取网站信息
 * @author tcyeee
 */
enum class BookmarkLinkType {
    // 域名（正常网站地址，会正常抓取标题/图标等信息）
    DOMAIN,
    // 本地地址（localhost / 127.0.0.1 / ::1）
    LOCAL,
    // 纯 IP 地址
    IP,
    // 其他（预留）
    OTHER
}
