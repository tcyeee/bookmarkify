package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.entity.WebsiteCategory

interface IWebsiteCategoryService : IService<WebsiteCategory> {
    /** 全部启用的分类，按 sort 升序 */
    fun activeCandidates(): List<WebsiteCategory>
}
