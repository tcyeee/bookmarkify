package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import com.baomidou.mybatisplus.core.metadata.IPage
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.BookmarkPingLogSearchParams
import top.tcyeee.bookmarkify.entity.BookmarkPingLogVO
import top.tcyeee.bookmarkify.server.IBookmarkPingLogService

@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/bookmark-ping-log")
class AdminBookmarkPingLogController(
    private val bookmarkPingLogService: IBookmarkPingLogService,
) {

    @PostMapping("/all")
    fun getAllLogs(@RequestBody params: BookmarkPingLogSearchParams): IPage<BookmarkPingLogVO> =
        bookmarkPingLogService.adminListAll(params)
}
