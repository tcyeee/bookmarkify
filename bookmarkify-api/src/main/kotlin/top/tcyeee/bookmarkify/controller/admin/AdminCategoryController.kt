package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import org.springframework.web.bind.annotation.*
import top.tcyeee.bookmarkify.entity.CategorySaveParams
import top.tcyeee.bookmarkify.entity.entity.WebsiteCategory
import top.tcyeee.bookmarkify.server.IWebsiteCategoryService

@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/category")
class AdminCategoryController(
    private val websiteCategoryService: IWebsiteCategoryService,
) {
    @PostMapping("/list")
    fun list(): List<WebsiteCategory> = websiteCategoryService.listAll()

    @PostMapping("/save")
    fun save(@RequestBody params: CategorySaveParams): WebsiteCategory =
        websiteCategoryService.saveCategory(params)

    @PostMapping("/{id}/delete")
    fun delete(@PathVariable id: String) = websiteCategoryService.softDelete(id)
}
