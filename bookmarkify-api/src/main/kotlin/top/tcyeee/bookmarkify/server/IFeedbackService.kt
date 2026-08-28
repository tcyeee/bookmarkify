package top.tcyeee.bookmarkify.server

import com.baomidou.mybatisplus.core.metadata.IPage
import top.tcyeee.bookmarkify.entity.FeedbackBatchParams
import top.tcyeee.bookmarkify.entity.FeedbackSearchParams
import top.tcyeee.bookmarkify.entity.FeedbackSubmitParams
import top.tcyeee.bookmarkify.entity.FeedbackTargetVO
import top.tcyeee.bookmarkify.entity.FeedbackVO

/**
 * 网站反馈组件：公开提交接口 + 后台收件箱。
 *
 * 「所属产品」是一份运行时可增删的列表（`feedback_target`），不是 Kotlin enum，所以既不进
 * `enums.generated.ts`，也不由 `SharedEnumContractTest` 约束。
 */
interface IFeedbackService {

    /** 表为空时补种默认目标（Bookmarkify / Vialite / AgentTool），供 AppInit 调用 */
    fun ensureDefaultTargets()

    /** 当前的目标名列表（按 sort 升序），公开接口用来告诉调用方合法取值 */
    fun listTargetNames(): List<String>

    /** 后台：带反馈计数的目标列表 */
    fun listTargets(): List<FeedbackTargetVO>

    /** 后台：新增一个目标，返回新行 */
    fun addTarget(name: String?): FeedbackTargetVO

    /** 后台：删除一个目标（不影响已存在的历史反馈） */
    fun deleteTarget(id: String)

    /**
     * 公开：提交一条反馈。[target] 必须命中当前目标列表，[content] 必填。
     * [sourceIp] / [userAgent] 由控制器从请求里取，仅存档不参与业务。
     */
    fun submit(params: FeedbackSubmitParams, sourceIp: String?, userAgent: String?)

    /** 后台：分页查询反馈 */
    fun adminList(params: FeedbackSearchParams): IPage<FeedbackVO>

    /** 后台：未读总数（给菜单 / 页面角标用） */
    fun unreadCount(): Long

    /** 后台：批量标记已读 / 未读 */
    fun markRead(params: FeedbackBatchParams)

    /** 后台：批量删除 */
    fun delete(ids: List<String>)
}
