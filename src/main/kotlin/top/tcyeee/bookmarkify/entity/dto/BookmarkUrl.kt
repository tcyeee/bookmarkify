package top.tcyeee.bookmarkify.entity.dto

/**
 * @author tcyeee
 * @date 3/11/24 18:29
 */
data class BookmarkUrl(
    var urlScheme: String, // http or https
    var urlHost: String, // sfz.uzuzuz.com.cn
    var urlQuery: String, // region=150303&birthday=19520807&sex=2&num=19&r=82
    var urlBase: String, // http://sfz.uzuzuz.com.cn
    var urlFull: String, // http://sfz.uzuzuz.com.cn/?region=150303%26birthday=19520807%26sex=2%26num=19%26r=82
    var urlPath: String,
)