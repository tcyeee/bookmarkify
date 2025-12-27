package top.tcyeee.bookmarkify.controller.bookmark

import cn.dev33.satoken.annotation.SaIgnore
import cn.hutool.core.util.IdUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.BookmarkDetail
import top.tcyeee.bookmarkify.entity.BookmarkUpdataPrams
import top.tcyeee.bookmarkify.entity.HomeItemShow
import top.tcyeee.bookmarkify.entity.HomeItemSortParams
import top.tcyeee.bookmarkify.entity.entity.Bookmark
import top.tcyeee.bookmarkify.entity.entity.BookmarkUserLink
import top.tcyeee.bookmarkify.entity.entity.HomeItem
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.IBookmarkUserLinkService
import top.tcyeee.bookmarkify.server.IHomeItemService
import top.tcyeee.bookmarkify.utils.BaseUtils
import java.util.function.Consumer
import java.util.stream.Collectors

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
    @PostMapping("/query")
    @Operation(summary = "我的书签")
    fun query(): List<HomeItemShow> = homeItemService.findShowByUid(BaseUtils.uid())

//    @PostMapping("/upload")
//    @Operation(summary = "书签上传", parameters = [Parameter(name = "file", description = "书签文件.html")])
//    fun upload(file: MultipartFile): List<BookmarkDetail> = updateBookmark(file, BaseUtils.uid())

    @PostMapping("/sort")
    @Operation(summary = "排序")
    fun sort(@RequestBody params: List<HomeItemSortParams>): Boolean = true
        .also { homeItemService.sort(params) }

    @PostMapping("/delete")
    @Operation(summary = "删除(仅删除桌面排序)")
    fun delete(@RequestBody params: List<String>) = params.forEach(homeItemService::deleteOne)

    @PostMapping("/update")
    @Operation(summary = "修改")
    fun update(@RequestBody params: BookmarkUpdataPrams): Boolean = bookmarkUserLinkService.updateOne(params)

    @GetMapping("/addOne")
    @Operation(summary = "添加书签")
    fun addOne(@RequestParam url: String) = bookmarkService.addOne(url, BaseUtils.uid())

    @SaIgnore
    @GetMapping("/check")
    fun check(@RequestParam url: String) = bookmarkService.check(url)
}
