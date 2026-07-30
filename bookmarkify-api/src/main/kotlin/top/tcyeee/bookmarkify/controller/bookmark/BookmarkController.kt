package top.tcyeee.bookmarkify.controller.bookmark

import com.baomidou.mybatisplus.core.metadata.IPage
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.throttle.Throttle
import top.tcyeee.bookmarkify.entity.AllOfMyBookmarkParams
import top.tcyeee.bookmarkify.entity.BookmarkImportPreviewVO
import top.tcyeee.bookmarkify.entity.BookmarkOpenParams
import top.tcyeee.bookmarkify.entity.BookmarkPinParams
import top.tcyeee.bookmarkify.entity.BookmarkSearchVO
import top.tcyeee.bookmarkify.entity.BookmarkShow
import top.tcyeee.bookmarkify.entity.BookmarkUpdatePrams
import top.tcyeee.bookmarkify.entity.CreateDirParams
import top.tcyeee.bookmarkify.entity.MoveNodeParams
import top.tcyeee.bookmarkify.entity.RenameDirParams
import top.tcyeee.bookmarkify.entity.UserLayoutNodeVO
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.IBookmarkUserLinkService
import top.tcyeee.bookmarkify.server.IUserLayoutNodeService
import top.tcyeee.bookmarkify.server.IUserPreferenceService
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
    private val preferenceService: IUserPreferenceService,
    private val layoutNodeService: IUserLayoutNodeService,
) {

    @Operation(summary = "通过书签简称/标题/描述/根域名,搜索书签")
    @PostMapping("/search")
    fun search(@RequestParam name: String): List<BookmarkSearchVO> = bookmarkService.search(name)

    @Operation(summary = "查看我的全部书签")
    @PostMapping("/list")
    fun list(@RequestBody params: AllOfMyBookmarkParams): IPage<BookmarkShow> =
        bookmarkService.allOfMyBookmark(BaseUtils.uid(), params)

    @PostMapping("/query")
    @Operation(summary = "我的桌面布局")
    fun query(): UserLayoutNodeVO = layoutNodeService.layout(BaseUtils.uid())

    @Throttle(interval = 5000)
    @PostMapping("/upload/preview")
    @Operation(summary = "书签导入预览（不写库）")
    fun uploadPreview(@RequestParam file: MultipartFile): BookmarkImportPreviewVO =
        bookmarkService.previewImport(file, BaseUtils.uid())

    @Throttle(interval = 5000)
    @PostMapping("/upload")
    @Operation(summary = "书签上传")
    fun upload(
        @RequestParam file: MultipartFile,
        @RequestParam(required = false) skipUrls: List<String>?,
    ): List<UserLayoutNodeVO> =
        bookmarkService.importBookmarkFile(file, BaseUtils.uid(), skipUrls?.toHashSet() ?: emptySet())

    /**
     * 这里的排序信息仅仅只更改用户配置中的排序数据库
     * 所以单将排序信息放到用户配置中，而不是直接写到用户桌面布局信息中, 是考虑到用户每一次排序都会导致大量的数据变动
     * 处于效率考虑，所以才单独进行拆分, 页面刷新的时候查询到桌面布局信息和用户配置中的排序信息进行组合，然后返回.
     *
     * @param params key: layout-node-id , value: sort
     */
    @PostMapping("/sort")
    @Operation(summary = "排序")
    fun sort(@RequestBody params: Map<String, Int>): Boolean = preferenceService.sort(BaseUtils.uid(), params)

    @PostMapping("/createDir")
    @Operation(summary = "创建文件夹")
    fun createDir(@RequestBody params: CreateDirParams): UserLayoutNodeVO =
        layoutNodeService.createDir(params, BaseUtils.uid())

    @PostMapping("/renameDir")
    @Operation(summary = "修改文件夹名称")
    fun renameDir(@RequestBody params: RenameDirParams): Boolean =
        layoutNodeService.renameDir(params, BaseUtils.uid())

    @PostMapping("/moveNode")
    @Operation(summary = "移动书签节点（移入文件夹 / 移出到根目录）")
    fun moveNode(@RequestBody params: MoveNodeParams): UserLayoutNodeVO =
        layoutNodeService.moveNode(params, BaseUtils.uid())

    /**
     * @param params layout元素ID
     */
    @PostMapping("/delete")
    @Operation(summary = "删除用户的自定义书签以及桌面元素")
    fun delete(@RequestBody params: List<String>) = layoutNodeService.deleteByIds(params, BaseUtils.uid())

    @Throttle
    @PostMapping("/update")
    @Operation(summary = "修改")
    fun update(@RequestBody params: BookmarkUpdatePrams): Boolean =
        bookmarkUserLinkService.updateOne(params, BaseUtils.uid())

    @Throttle
    @PostMapping("/pin")
    @Operation(summary = "置顶/取消置顶")
    fun pin(@RequestBody params: BookmarkPinParams): Boolean =
        bookmarkUserLinkService.setPinned(params.linkId, params.pinned, BaseUtils.uid())

    // 会创建 bookmark/user_layout_node/bookmark_user_link 三张表的写入，不应该用 GET 承载
    // （历史遗留问题：GET 请求可能被浏览器预取/代理缓存/爬虫意外重放，触发非预期的写操作）
    @Throttle
    @PostMapping("/addOne")
    @Operation(summary = "通过URL添加书签")
    fun addOne(@RequestParam url: String): UserLayoutNodeVO = bookmarkService.addOne(url, BaseUtils.uid())

    @GetMapping("/linkOne")
    @Operation(summary = "关联书签")
    fun linkOne(@RequestParam bookmarkId: String) = bookmarkService.linkOne(bookmarkId, BaseUtils.uid())

    // 不加 @Throttle：该注解按 uid+方法名 限流，不区分参数，会导致连续打开两个不同书签时
    // 第二次被拦截、计数丢失，与"尽量准确计数"的目的相悖。这里只是一次单行原子自增，
    // 前端也是 fire-and-forget 调用，滥用成本收益比很低。
    @PostMapping("/open")
    @Operation(summary = "记录一次书签打开(仅做计数)")
    fun open(@RequestBody params: BookmarkOpenParams): Boolean =
        bookmarkUserLinkService.recordOpen(params.linkId, BaseUtils.uid())
}
