package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import org.springframework.web.bind.annotation.*
import top.tcyeee.bookmarkify.config.audit.AdminAudit
import top.tcyeee.bookmarkify.entity.CategorySaveParams
import top.tcyeee.bookmarkify.entity.entity.Category
import top.tcyeee.bookmarkify.server.ICategoryService

@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/category")
class AdminCategoryController(
    private val categoryService: ICategoryService,
) {
    @PostMapping("/list")
    fun list(): List<Category> = categoryService.listAll()

    @AdminAudit("保存分类")
    @PostMapping("/save")
    fun save(@RequestBody params: CategorySaveParams): Category =
        categoryService.saveCategory(params)

    @AdminAudit("删除分类")
    @PostMapping("/{id}/delete")
    fun delete(@PathVariable id: String) = categoryService.softDelete(id)
}
