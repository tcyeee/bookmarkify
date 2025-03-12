package top.tcyeee.bookmarkify.entity.dto

import cn.hutool.core.util.URLUtil
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.config.log

/**
 * @author tcyeee
 * @date 3/11/24 18:29
 */
class BookmarkUrl(urlStr: String) {
    var urlScheme: String  // http or https
    var urlHost: String    // sfz.uzuzuz.com.cn
    var urlPath: String
    var urlFull: String    // http://sfz.uzuzuz.com.cn/?region=15030

    private var urlQuery: String? = null   // region=150303&birthday=19520807&sex=2&num=19&r=82
    private var urlBase: String? = null    // http://sfz.uzuzuz.com.cn

    init {
        // 如果不是http://,或者htts://开始,则手动补全
        var processedUrl = urlStr
        if (!processedUrl.startsWith("https://") && !processedUrl.startsWith("http://"))
            processedUrl = "https://$processedUrl"
        try {
            val url = URLUtil.toUrlForHttp(processedUrl)
            this.urlScheme = url.protocol
            this.urlHost = url.authority
            this.urlQuery = url.query
            this.urlPath = url.path
            this.urlFull = processedUrl
            this.urlBase = "${url.protocol}://${url.host}"
        } catch (e: Exception) {
            log.info("用户添加了一个非法地址:{}", processedUrl)
            throw CommonException(ErrorType.E303)
        }
    }
}