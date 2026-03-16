package top.tcyeee.bookmarkify.server

import top.tcyeee.bookmarkify.entity.dto.IframelyResponse

/**
 * @author tcyeee
 * @date 3/14/26 14:07
 */
interface IApiService {
    fun queryWebsiteInfo(domain: String): IframelyResponse
}