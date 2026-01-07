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
import top.tcyeee.bookmarkify.entity.LayoutNodeSort
import top.tcyeee.bookmarkify.entity.UserLayoutNodeVO
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.IBookmarkUserLinkService
import top.tcyeee.bookmarkify.server.IHomeItemService
import top.tcyeee.bookmarkify.server.IUserLayoutNodeService
import top.tcyeee.bookmarkify.server.IUserPreferenceService
import top.tcyeee.bookmarkify.utils.APP_UID
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
    private val preferenceService: IUserPreferenceService,
    private val layoutNodeService: IUserLayoutNodeService
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
    fun query(): UserLayoutNodeVO = layoutNodeService.layout(APP_UID)

    @PostMapping("/upload")
    @Operation(summary = "书签上传")
    fun upload(@RequestParam file: MultipartFile): List<HomeItemShow>? =
        bookmarkService.importBookmarkFile(file, BaseUtils.uid())

    /**
     * 这里的排序信息仅仅只更改用户配置中的排序数据库
     * 所以单将排序信息放到用户配置中，而不是直接写到用户桌面布局信息中, 是考虑到用户每一次排序都会导致大量的数据变动
     * 处于效率考虑，所以才单独进行拆分, 页面刷新的时候查询到桌面布局信息和用户配置中的排序信息进行组合，然后返回.
     *
     * @param params key: layout-node-id , value: sort
     */
    @PostMapping("/sort")
    @Operation(summary = "排序")
    fun sort(@RequestBody params: Map<String, Int>): Boolean = preferenceService.sort(APP_UID, params)

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
