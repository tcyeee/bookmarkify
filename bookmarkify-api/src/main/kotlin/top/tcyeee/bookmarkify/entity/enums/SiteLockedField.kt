package top.tcyeee.bookmarkify.entity.enums

/**
 * 站点级的、被管理员手工锁定不允许自动抓取覆盖的字段。
 *
 * 与 [BookmarkLockedField] 分成两个枚举而不是合成一个：锁是跟着**行**走的，
 * `site.locked_fields` 里出现 `TITLE` 毫无意义（站点没有标题，页面才有），
 * 合成一个枚举等于允许把锁加到不存在的字段上，而这种错误没有任何地方会报出来。
 *
 * 语义与 [BookmarkLockedField] 一致：手工编辑 → 加锁；显式接受抓取值 → 解锁。
 * 自动抓取链路只能读锁、不能改锁。
 */
enum class SiteLockedField {
    /** 站点全名（og:site_name / manifest.name） */
    BRAND_NAME,

    /** 站点短名（manifest.short_name），磁贴文案用 */
    SHORT_NAME,
}
