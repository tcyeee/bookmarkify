package top.tcyeee.bookmarkify.controller.bookmark

import cn.dev33.satoken.annotation.SaCheckLogin
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.server.IBookmarkService

/**
 * @author tcyeee
 * @date 1/7/26 22:13
 */
@RestController
@Tag(name = "书签管理相关")
@RequestMapping("/admin/bookmark")
@SaCheckLogin(type = "admin")
class BookmarkAdminController(private val bookmarkService: IBookmarkService) {

    @Operation(summary = "查看全部的书签")
    @GetMapping("/list")
    fun list(@RequestParam(required = false) name: String?): List<BookmarkEntity> =
        bookmarkService.adminListAll(name)

    @Operation(summary = "修改单个书签信息")
    @PostMapping("/update")
    fun update(@RequestBody params: Map<String, Any?>): Boolean =
        bookmarkService.adminUpdateOne(
            id = params["id"] as String,
            appName = params["appName"] as String?,
            title = params["title"] as String?,
            description = params["description"] as String?,
            iconBase64 = params["iconBase64"] as String?,
            isActivity = params["isActivity"] as Boolean?,
            maximalLogoSize = (params["maximalLogoSize"] as? Number)?.toInt()
        )
}
