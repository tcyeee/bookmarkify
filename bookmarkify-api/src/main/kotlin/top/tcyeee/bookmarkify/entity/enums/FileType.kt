package top.tcyeee.bookmarkify.entity.enums

/**
 * @author tcyeee
 * @date 12/29/25 13:56
 */
/**
 * 用户上传文件的类型，同时决定 OSS 目录与大小上限。
 *
 * 只覆盖**用户主动上传**的文件（`user_file` 表）。站点图标/社交图/截图由 scrapper 写入
 * 它自己的 `OSS_KEY_PREFIX` 前缀，不走这里 —— 原先的 `WEBSITE_LOGO` / `WEBSITE_OG` 是本地
 * Jsoup 转存路径的遗留，该路径已随 site_asset 改造下线，枚举项也一并移除。
 */
enum class FileType(
    val limit: Int,         // 文件大小限制
    val folder: String,     // 文件保存位置(oss下的文件夹路径)
    var type: String,       // 文件类型限制
) {
    AVATAR(5 * 1024 * 1024, "bookmarkify/avatar", "image/"),
    BACKGROUND(10 * 1024 * 1024, "bookmarkify/bac", "image/");

    fun isImg() = this.type == "image/"
}