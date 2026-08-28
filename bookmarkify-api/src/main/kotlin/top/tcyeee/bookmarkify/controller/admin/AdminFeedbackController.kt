package top.tcyeee.bookmarkify.controller.admin

import cn.dev33.satoken.annotation.SaCheckRole
import com.baomidou.mybatisplus.core.metadata.IPage
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import top.tcyeee.bookmarkify.entity.FeedbackBatchParams
import top.tcyeee.bookmarkify.entity.FeedbackSearchParams
import top.tcyeee.bookmarkify.entity.FeedbackTargetSaveParams
import top.tcyeee.bookmarkify.entity.FeedbackTargetVO
import top.tcyeee.bookmarkify.entity.FeedbackVO
import top.tcyeee.bookmarkify.server.IFeedbackService

/** 网站反馈组件后台：收件箱 + 「所属产品」维护 */
@RestController
@SaCheckRole(value = ["ADMIN"], type = "ADMIN")
@RequestMapping("/admin/feedback")
class AdminFeedbackController(
    private val feedbackService: IFeedbackService,
) {

    @PostMapping("/list")
    fun list(@RequestBody params: FeedbackSearchParams): IPage<FeedbackVO> = feedbackService.adminList(params)

    @PostMapping("/unread-count")
    fun unreadCount(): Long = feedbackService.unreadCount()

    @PostMapping("/read")
    fun markRead(@RequestBody params: FeedbackBatchParams): Boolean {
        feedbackService.markRead(params)
        return true
    }

    @PostMapping("/delete")
    fun delete(@RequestBody params: FeedbackBatchParams): Boolean {
        feedbackService.delete(params.ids)
        return true
    }

    // ── 所属产品 ──

    @PostMapping("/targets")
    fun targets(): List<FeedbackTargetVO> = feedbackService.listTargets()

    @PostMapping("/targets/add")
    fun addTarget(@RequestBody params: FeedbackTargetSaveParams): FeedbackTargetVO =
        feedbackService.addTarget(params.name)

    @PostMapping("/targets/{id}/delete")
    fun deleteTarget(@PathVariable id: String): Boolean {
        feedbackService.deleteTarget(id)
        return true
    }
}
