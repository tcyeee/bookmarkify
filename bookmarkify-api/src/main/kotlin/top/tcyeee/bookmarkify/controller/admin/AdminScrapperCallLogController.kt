package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import com.baomidou.mybatisplus.core.metadata.IPage
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.ScrapperCallLogSearchParams
import top.tcyeee.bookmarkify.entity.ScrapperCallLogVO
import top.tcyeee.bookmarkify.server.IScrapperCallLogService

@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/scrapper-call-log")
class AdminScrapperCallLogController(
    private val scrapperCallLogService: IScrapperCallLogService,
) {

    @PostMapping("/all")
    fun getAllLogs(@RequestBody params: ScrapperCallLogSearchParams): IPage<ScrapperCallLogVO> =
        scrapperCallLogService.adminListAll(params)
}
