package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.WebsiteCategory
import top.tcyeee.bookmarkify.mapper.WebsiteCategoryMapper
import top.tcyeee.bookmarkify.server.IWebsiteCategoryService

@Service
class WebsiteCategoryServiceImpl :
    IWebsiteCategoryService, ServiceImpl<WebsiteCategoryMapper, WebsiteCategory>() {

    override fun activeCandidates(): List<WebsiteCategory> =
        ktQuery().eq(WebsiteCategory::deleted, false).orderByAsc(WebsiteCategory::sort).list()
}
