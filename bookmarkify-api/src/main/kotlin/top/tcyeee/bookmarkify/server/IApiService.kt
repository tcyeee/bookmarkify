package top.tcyeee.bookmarkify.server

import top.tcyeee.bookmarkify.entity.WebsiteInfoVO

/**
 * @author tcyeee
 * @date 3/14/26 14:07
 */
interface IApiService {
    fun queryWebsiteInfo(domain: String): WebsiteInfoVO
}