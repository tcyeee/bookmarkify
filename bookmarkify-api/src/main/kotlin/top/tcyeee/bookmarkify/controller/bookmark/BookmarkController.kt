package top.tcyeee.bookmarkify.controller.bookmark

import cn.dev33.satoken.annotation.SaIgnore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.throttle.Throttle
import top.tcyeee.bookmarkify.entity.AllOfMyBookmarkParams
import top.tcyeee.bookmarkify.entity.BookmarkShow

import top.tcyeee.bookmarkify.entity.BookmarkUpdatePrams
import top.tcyeee.bookmarkify.entity.HomeItemShow
import top.tcyeee.bookmarkify.entity.HomeItemSortParams
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.IBookmarkUserLinkService
import top.tcyeee.bookmarkify.server.IHomeItemService
import top.tcyeee.bookmarkify.utils.BaseUtils

/**
 * @author tcyeee
 * @date 3/12/25 13:57
 */
@RestController
@Tag(name = "书签相关")
@RequestMapping("/bookmark")
class BookmarksController(
    private val bookmarkUserLinkService: IBookmarkUserLinkService,
    private val bookmarkService: IBookmarkService,
    private val homeItemService: IHomeItemService,
) {

    @Operation(summary = "通过书签简称/标题/描述/根域名,搜索书签")
    @PostMapping("/search")
    fun search(@RequestParam name: String): List<BookmarkEntity> = bookmarkService.search(name)

    @Operation(summary = "查看我的全部书签")
    @PostMapping("/list")
    fun list(@RequestBody params: AllOfMyBookmarkParams): List<BookmarkShow> =
        bookmarkService.allOfMyBookmark(BaseUtils.uid(), params)

    @PostMapping("/query")
    @Operation(summary = "我的桌面布局")
    fun query(): List<HomeItemShow> = homeItemService.findShowByUid(BaseUtils.uid())

    @PostMapping("/upload")
    @Operation(summary = "书签上传")
    fun upload(@RequestParam file: MultipartFile): List<HomeItemShow>? = bookmarkService.importBookmarkFile(file, BaseUtils.uid())

    @PostMapping("/sort")
    @Operation(summary = "排序")
    fun sort(@RequestBody params: List<HomeItemSortParams>): Boolean = true.also { homeItemService.sort(params) }

    @PostMapping("/delete")
    @Operation(summary = "删除(仅删除桌面排序)")
    fun delete(@RequestBody params: List<String>) = params.forEach(homeItemService::deleteOne)

    @Throttle
    @PostMapping("/update")
    @Operation(summary = "修改")
    fun update(@RequestBody params: BookmarkUpdatePrams): Boolean = bookmarkUserLinkService.updateOne(params)

    @Throttle
    @GetMapping("/addOne")
    @Operation(summary = "添加书签")
    fun addOne(@RequestParam url: String) = bookmarkService.addOne(url, BaseUtils.uid())

    @GetMapping("/linkOne")
    @Operation(summary = "关联书签")
    fun linkOne(@RequestParam bookmarkId: String) = bookmarkService.linkOne(bookmarkId, BaseUtils.uid())

    @Throttle
    @SaIgnore
    @GetMapping("/check")
    fun queryOne(@RequestParam url: String) =
        bookmarkService.addOne(url, "6a5775f7-e0ed-4883-aba5-06e001386c6d")
}
