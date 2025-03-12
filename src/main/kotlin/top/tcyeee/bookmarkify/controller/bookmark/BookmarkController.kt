package top.tcyeee.bookmarkify.controller.bookmark

import cn.hutool.core.util.IdUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.dto.BookmarkUpdataPrams
import top.tcyeee.bookmarkify.entity.po.Bookmark
import top.tcyeee.bookmarkify.entity.po.BookmarkUserLink
import top.tcyeee.bookmarkify.entity.po.HomeItem
import top.tcyeee.bookmarkify.entity.response.BookmarkAddOneParams
import top.tcyeee.bookmarkify.entity.response.BookmarkDetail
import top.tcyeee.bookmarkify.entity.response.HomeItemShow
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.IBookmarkUserLinkService
import top.tcyeee.bookmarkify.server.IHomeItemService
import top.tcyeee.bookmarkify.utils.BookmarkUtils
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

    @GetMapping("/my")
    @Operation(summary = "我的书签")
    fun myBookmarks(): List<HomeItemShow> {
        return homeItemService.findShowByUid(BaseUtils.currentUid())
    }

    @PostMapping("/upload")
    @Operation(summary = "书签上传", parameters = [Parameter(name = "file", description = "书签文件.html")])
    fun upload(file: MultipartFile): List<BookmarkDetail> {
        return updateBookmark(file, BaseUtils.currentUid())
    }

    @PutMapping("/sort")
    @Operation(summary = "排序")
    fun sort(@RequestBody params: List<HomeItem>): Boolean {
        return homeItemService.sort(params)
    }

    @PutMapping("/delete")
    @Operation(summary = "删除")
    fun delete(@RequestBody params: List<HomeItem>): Boolean {
        return homeItemService.delete(params)
    }

    @PutMapping("/update")
    @Operation(summary = "修改")
    fun update(@RequestBody params: BookmarkUpdataPrams): Boolean {
        return bookmarkUserLinkService.updateOne(params)
    }

    /**
     * 手动添加一个书签,每次添加都需要检查一次网站的信息
     *
     * @param params 更新参数
     * @return 用户全部网站信息
     */
    @PostMapping("/addOne")
    @Operation(summary = "添加书签")
    fun addOne(@RequestBody params: BookmarkAddOneParams): List<HomeItemShow> {
        bookmarkService.addOne(params.url, BaseUtils.currentUid())
        return homeItemService.findShowByUid(BaseUtils.currentUid())
    }

    /**
     * 整理上传的全部书签
     *
     * @param file 书签文件(chrome)
     * @return 整理好的书签文件
     */
    private fun updateBookmark(file: MultipartFile, uid: String): List<BookmarkDetail> {
        val allBookmarkDetail: List<BookmarkDetail> = BookmarkUtils.initChromeBookmark(file)

        // 清洗拿到去重后的全局Bookmark,包含了原有的和新增的  key:host value:BookmarkDetail
        val allBookmark: MutableMap<String, Bookmark> = HashMap()
        allBookmarkDetail.forEach(Consumer { item: BookmarkDetail ->
            allBookmark[item.url.urlHost] = item.bookmark
        })

        // 找到数据库已经有的,移出掉,只留下所有需要新增的数据
        val hasSave: List<Bookmark> = bookmarkService.lambdaQuery().`in`(Bookmark::urlHost, allBookmark.keys).list()
        val hasSaveHost = hasSave.stream().map<Any>(Bookmark::urlHost).collect(Collectors.toSet())
        allBookmark.keys.removeIf { o: String -> hasSaveHost.contains(o) }

        // 存入所有书签
        val collect: MutableList<Bookmark> = ArrayList(allBookmark.values)
        collect.forEach(Consumer { bookmark: Bookmark -> bookmark.id = IdUtil.fastUUID() })
        bookmarkService.saveBatch(collect)

        // 新增书签组合成可供查询的Bookmark数据库key:host,将新增的ID返回到原始数据库
        collect.addAll(hasSave)
        val database: Map<String, Bookmark> = collect.associateBy { it.urlHost }

        allBookmarkDetail.forEach(Consumer { item: BookmarkDetail ->
            val id: String = database[item.bookmark.urlHost]?.id.toString()
            item.bookmark.id = id
        })

        // 存入用户-书签关联
        val links: MutableList<BookmarkUserLink> = ArrayList()
        allBookmarkDetail.forEach(Consumer { item: BookmarkDetail ->
            val userLink = BookmarkUserLink(item, uid)
            item.bookmarkUserLinkId = userLink.id
            links.add(userLink)
        })
        bookmarkUserLinkService.saveBatch(links)

        // 存入桌面布局
        val homeItems: List<HomeItem> = this.changeToItem(allBookmarkDetail, uid)
        homeItemService.saveBatch(homeItems)

        log.info("用户{}书签导入完成,共计导入{}条", uid, allBookmarkDetail.size)
        return allBookmarkDetail
    }

    /**
     * 整理出所有的桌面Itmes
     *
     * @param allBookmark 待整理
     * @return 整理完成
     */
    private fun changeToItem(allBookmark: List<BookmarkDetail>, uid: String): List<HomeItem> {
        val dirList: MutableMap<String, MutableList<String>> = HashMap() // 根据最后的目录整理书签, 结构为 目录名:用户-书签-IDS
        val rootBookmarks: MutableList<HomeItem> = ArrayList() // 根路径书签

        allBookmark.forEach(Consumer { item: BookmarkDetail ->
            /* 添加书签 */
            if (item.paths.isEmpty()) {
                rootBookmarks.add(HomeItem(item.bookmark, uid, item.bookmarkUserLinkId))
            } else {
                val dirStr: String = item.paths.last()
                dirList.computeIfAbsent(dirStr) { ArrayList() }.add(item.bookmarkUserLinkId)
            }
        })

        // 向目录中存放书签
        dirList.forEach { (dirStr: String?, bookmarkIds: MutableList<String>) ->
            rootBookmarks.add(HomeItem(bookmarkIds, dirStr, uid))
        }
        return rootBookmarks
    }
}