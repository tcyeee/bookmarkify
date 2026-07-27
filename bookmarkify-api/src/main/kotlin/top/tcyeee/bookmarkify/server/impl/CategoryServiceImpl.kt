package top.tcyeee.bookmarkify.server.impl

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.entity.CategorySaveParams
import top.tcyeee.bookmarkify.entity.entity.Category
import top.tcyeee.bookmarkify.mapper.CategoryMapper
import top.tcyeee.bookmarkify.server.ICategoryService
import java.time.LocalDateTime

@Service
class CategoryServiceImpl :
    ICategoryService, ServiceImpl<CategoryMapper, Category>() {

    override fun activeCandidates(): List<Category> =
        ktQuery().eq(Category::deleted, false).orderByAsc(Category::sort).list()

    override fun listAll(): List<Category> =
        ktQuery().eq(Category::deleted, false).orderByAsc(Category::sort).list()

    override fun saveCategory(params: CategorySaveParams): Category {
        val now = LocalDateTime.now()
        val entity = if (params.id.isNullOrBlank()) {
            Category(
                id = IdUtil.fastUUID(), slug = params.slug, name = params.name,
                description = params.description, color = params.color, sort = params.sort,
                createTime = now, lastModified = now,
            )
        } else {
            val existed = getById(params.id) ?: throw IllegalArgumentException("分类不存在: ${params.id}")
            existed.slug = params.slug
            existed.name = params.name
            existed.description = params.description
            existed.color = params.color
            existed.sort = params.sort
            existed.lastModified = now
            existed
        }
        saveOrUpdate(entity)
        return entity
    }

    override fun softDelete(id: String) {
        ktUpdate().eq(Category::id, id).set(Category::deleted, true).update()
    }
}
