package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import com.baomidou.mybatisplus.core.metadata.IPage
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.AiCallLogSearchParams
import top.tcyeee.bookmarkify.entity.AiCallLogVO
import top.tcyeee.bookmarkify.server.IAiCallLogService

@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/ai-call-log")
class AdminAiCallLogController(
    private val aiCallLogService: IAiCallLogService,
) {

    @PostMapping("/all")
    fun getAllLogs(@RequestBody params: AiCallLogSearchParams): IPage<AiCallLogVO> =
        aiCallLogService.adminListAll(params)
}
