package top.tcyeee.bookmarkify.entity.enums

/**
 * @author tcyeee
 * @date 12/29/25 13:56
 */
enum class FileType(
    val limit: Int,         // 文件大小限制
    val folder: String,     // 文件保存位置(oss下的文件夹路径)
    var type: String,       // 文件类型限制
) {
    AVATAR(5 * 1024 * 1024, "bookmarkify/avatar", "image/"),
    BACKGROUND(10 * 1024 * 1024, "bookmarkify/background", "image/"),
    WEBSITE_LOGO(1 * 1024 * 1024, "bookmarkify/logo", "image/"),
    WEBSITE_OG(5 * 1024 * 1024, "bookmarkify/og", "image/");

    fun isImg() = this.type == "image/"
}