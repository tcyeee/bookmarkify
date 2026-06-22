package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import com.baomidou.mybatisplus.core.metadata.IPage
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import top.tcyeee.bookmarkify.entity.BookmarkAdminVO
import top.tcyeee.bookmarkify.entity.BookmarkCategoriesParams
import top.tcyeee.bookmarkify.entity.BookmarkIconUpdateParams
import top.tcyeee.bookmarkify.entity.BookmarkRefetchApplyParams
import top.tcyeee.bookmarkify.entity.BookmarkRefetchVO
import top.tcyeee.bookmarkify.entity.BookmarkSearchParams
import top.tcyeee.bookmarkify.entity.CategoryVO
import top.tcyeee.bookmarkify.server.IBookmarkService

/**
 * @author tcyeee
 * @date 1/6/26 15:52
 */
@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/bookmark")
class AdminBookmarkManageController(
    private val bookmarkService: IBookmarkService,
) {

    // 按条件获取全部的书签信息（带搜索与状态等筛选条件）
    @PostMapping("/all")
    fun getAllBookmarks(@RequestBody params: BookmarkSearchParams): IPage<BookmarkAdminVO> =
        bookmarkService.adminListAll(params)

    // 修改单个书签信息 (使用POST替换PUT)
    @PostMapping("/{bookmarkId}/update")
    fun updateBookmark(
        @PathVariable bookmarkId: Long, @RequestBody bookmarkData: Map<String, Any>
    ): ResponseEntity<Any> {
        // 实现更新单个书签信息的逻辑
        return ResponseEntity.ok().build()
    }

    // 修改单个书签的图标设置（图片内边距 iconPadding、图标背景色 iconBgColor）
    @PostMapping("/{bookmarkId}/icon")
    fun updateBookmarkIcon(
        @PathVariable bookmarkId: String, @RequestBody params: BookmarkIconUpdateParams
    ) = bookmarkService.adminUpdateIcon(bookmarkId, params)

    // 重新获取：重新解析网站标题与图标（不落库），返回预览数据供前端对比选择
    @PostMapping("/{bookmarkId}/refetch")
    fun refetchBookmark(@PathVariable bookmarkId: String): BookmarkRefetchVO =
        bookmarkService.adminRefetch(bookmarkId)

    // 应用重新获取的结果：按选择采用新标题/新图标并持久化
    @PostMapping("/{bookmarkId}/refetch/apply")
    fun applyRefetchBookmark(
        @PathVariable bookmarkId: String, @RequestBody params: BookmarkRefetchApplyParams
    ): BookmarkAdminVO = bookmarkService.adminApplyRefetch(bookmarkId, params)

    // 手动覆盖式设置某书签的分类
    @PostMapping("/{bookmarkId}/categories")
    fun updateCategories(
        @PathVariable bookmarkId: String, @RequestBody params: BookmarkCategoriesParams
    ): List<CategoryVO> = bookmarkService.adminUpdateCategories(bookmarkId, params.categoryIds)

    // 对某书签重新执行 DeepSeek 自动归类
    @PostMapping("/{bookmarkId}/categorize")
    fun recategorize(@PathVariable bookmarkId: String): List<CategoryVO> =
        bookmarkService.adminRecategorize(bookmarkId)

    // 删除单个书签信息 (使用POST替换DELETE)
    @PostMapping("/{bookmarkId}/delete")
    fun deleteBookmark(@PathVariable bookmarkId: Long): ResponseEntity<Void> {
        // 实现删除单个书签信息的逻辑
        return ResponseEntity.ok().build()
    }

    // 对某个书签进行活性检测
    @GetMapping("/{bookmarkId}/check")
    fun checkBookmarkActive(@PathVariable bookmarkId: Long): ResponseEntity<Map<String, Any>> {
        // 实现书签活性检测的逻辑
        return ResponseEntity.ok(mapOf("active" to true))
    }

    /* 书签集管理 */

    // 获取全部系统书签集信息
    @GetMapping("/collections/system")
    fun getAllSystemCollections(): ResponseEntity<List<Any>> {
        // 实现获取全部系统书签集的逻辑
        return ResponseEntity.ok(listOf())
    }

    // 添加一条系统书签集
    @PostMapping("/collections/system")
    fun addSystemCollection(@RequestBody collectionData: Map<String, Any>): ResponseEntity<Any> {
        // 实现添加系统书签集的逻辑
        return ResponseEntity.ok().build()
    }

    // 修改一条系统书签集 (使用POST替换PUT)
    @PostMapping("/collections/system/{collectionId}/update")
    fun updateSystemCollection(
        @PathVariable collectionId: Long, @RequestBody collectionData: Map<String, Any>
    ): ResponseEntity<Any> {
        // 实现修改系统书签集的逻辑
        return ResponseEntity.ok().build()
    }

    // 获取全部的用户书签集
    @GetMapping("/collections/user")
    fun getAllUserCollections(): ResponseEntity<List<Any>> {
        // 实现获取全部用户书签集的逻辑
        return ResponseEntity.ok(listOf())
    }

    // 修改一条用户书签集 (使用POST替换PUT)
    @PostMapping("/collections/user/{collectionId}/update")
    fun updateUserCollection(
        @PathVariable collectionId: Long, @RequestBody collectionData: Map<String, Any>
    ): ResponseEntity<Any> {
        // 实现修改用户书签集的逻辑
        return ResponseEntity.ok().build()
    }
}
