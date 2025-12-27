//package top.tcyeee.bookmarkify.utils
//
//import cn.hutool.core.util.URLUtil
//import org.jsoup.Jsoup
//import org.jsoup.nodes.Document
//import top.tcyeee.bookmarkify.config.exception.CommonException
//import top.tcyeee.bookmarkify.config.exception.ErrorType
//import top.tcyeee.bookmarkify.entity.dto.BookmarkUrlWrapper
//import top.tcyeee.bookmarkify.entity.dto.BookmarkWrapper
//import java.net.URL
//
///**
// * @author tcyeee
// * @date 3/10/24 17:32
// */
//object BookmarkUtils {
//
////    /**
////     * 获取目标网站LOGO,同时下载到本地
////     *
////     * @param document 解析后的网页文件
////     * @return url的存储地址
////     */
////    fun getLogoUrl(document: Document, urlPre: String, bookmark: Bookmark): String? {
////        // 尝试从 httpCommonIcoUrl 下载 logo，如果成功返回 true
////        val rawIconUrl = urlPre + bookmark.defaultIconUrl
////        if (downloadLogo(bookmark.httpCommonIcoUrl, rawIconUrl)) return bookmark.defaultIconUrl
////
////        // 获取文档中的 logo URL 下载 logo，如果成功返回 true
////        val logoUrl = this.checkLogoUrl(document) ?: return null
////        val storeUrl = "/favicon/${bookmark.id}.${FileUtil.extName(logoUrl)}"
////        if (downloadLogo(logoUrl, urlPre + storeUrl)) return storeUrl
////
////        return null
////    }
////
////    /**
////     * 将LOGO下载到本地
////     *
////     * @param logoUrl LOGO的线上地址
////     * @param storeUrl 存储地址
////     */
////    private fun downloadLogo(logoUrl: String, storeUrl: String): Boolean = runCatching {
////        HttpUtil.downloadFileFromUrl(logoUrl, File(storeUrl))
////        log.info("[CHECK] 获取到了书签LOGO:{}", storeUrl)
////    }.isSuccess
////
////    /**
////     * 从节点中解析图标LOGO
////     *
////     * @param document 网站解析后信息
////     * @return 可能为绝对路径, 可能为相对路径, 可能为空
////     */
////    private fun checkLogoUrl(document: Document): String? {
////        // 在标签中找到icon标签的href属性
////        val url = document.select("link").firstOrNull { listOf("icon", "shortcut icon").contains(it.attr("rel")) }
////            ?.attr("href") ?: return null
////
////        // 这里可能是相对路径,遇到了再说
////        // 对URL进行清洗(去除后面可能携带的参数)
////        val tmpUrl = URLUtil.toUrlForHttp(url)
////        return "${tmpUrl.protocol}://${tmpUrl.host}${tmpUrl.path}"
////    }
//}
