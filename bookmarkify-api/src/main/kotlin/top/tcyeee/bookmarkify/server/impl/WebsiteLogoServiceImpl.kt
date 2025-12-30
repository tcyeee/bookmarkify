package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.entity.WebsiteLogoEntity
import top.tcyeee.bookmarkify.mapper.WebsiteLogoMapper
import top.tcyeee.bookmarkify.server.IWebsiteLogoService

/**
 * 网站Logo Service 实现
 *
 * @author tcyeee
 * @date 12/29/25 15:05
 */
@Service
class WebsiteLogoServiceImpl : IWebsiteLogoService, ServiceImpl<WebsiteLogoMapper, WebsiteLogoEntity>() {
    override fun findByBookmarkId(id: String): List<WebsiteLogoEntity> {
        return ktQuery().eq(WebsiteLogoEntity::bookmarkId, id).list()
    }

    override fun updateMaximalLogoByBookmarkId(entity: WebsiteLogoEntity) {
        this.deleteLogoByBookmarkIdAndSize(entity.bookmarkId, entity.width)
        save(entity)
    }

    private fun deleteLogoByBookmarkIdAndSize(bookmarkId: String, size: Int): WebsiteLogoEntity? =
        ktQuery()
            .eq(WebsiteLogoEntity::bookmarkId, bookmarkId)
            .eq(WebsiteLogoEntity::width, size)
            .eq(WebsiteLogoEntity::height, size)
            .eq(WebsiteLogoEntity::isOgImg, false)
            .one()
            ?.also { removeById(it.id) }
}
