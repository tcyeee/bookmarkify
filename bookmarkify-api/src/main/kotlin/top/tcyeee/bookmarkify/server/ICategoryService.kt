package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.extension.service.IService
import top.tcyeee.bookmarkify.entity.CategorySaveParams
import top.tcyeee.bookmarkify.entity.entity.Category

interface ICategoryService : IService<Category> {
    /** 全部启用的分类，按 sort 升序 */
    fun activeCandidates(): List<Category>

    /** 后台：全部未删除分类，按 sort 升序 */
    fun listAll(): List<Category>

    /** 后台：新增或修改一条分类（id 为空=新增） */
    fun saveCategory(params: CategorySaveParams): Category

    /** 后台：软删一条分类 */
    fun softDelete(id: String)
}
