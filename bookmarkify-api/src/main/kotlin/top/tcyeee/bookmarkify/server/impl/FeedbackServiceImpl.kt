package top.tcyeee.bookmarkify.server.impl

import com.baomidou.mybatisplus.core.metadata.IPage
import com.baomidou.mybatisplus.extension.kotlin.KtQueryWrapper
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.FeedbackBatchParams
import top.tcyeee.bookmarkify.entity.FeedbackSearchParams
import top.tcyeee.bookmarkify.entity.FeedbackSubmitParams
import top.tcyeee.bookmarkify.entity.FeedbackTargetVO
import top.tcyeee.bookmarkify.entity.FeedbackVO
import top.tcyeee.bookmarkify.entity.entity.FeedbackEntity
import top.tcyeee.bookmarkify.entity.entity.FeedbackTargetEntity
import top.tcyeee.bookmarkify.mapper.FeedbackMapper
import top.tcyeee.bookmarkify.mapper.FeedbackTargetMapper
import top.tcyeee.bookmarkify.server.IFeedbackService

@Service
class FeedbackServiceImpl(
    private val targetMapper: FeedbackTargetMapper,
) : IFeedbackService, ServiceImpl<FeedbackMapper, FeedbackEntity>() {

    private val log = LoggerFactory.getLogger(javaClass)

    // ── 目标（所属产品） ──────────────────────────────────────────────

    override fun ensureDefaultTargets() {
        if (targetMapper.selectCount(null) > 0) return
        DEFAULT_TARGETS.forEachIndexed { i, name ->
            runCatching { targetMapper.insert(FeedbackTargetEntity(name = name, sort = i)) }
                .onFailure { log.warn("[Feedback] 默认目标补种失败(忽略): name=$name, err=${it.message}") }
        }
    }

    private fun allTargetsOrdered(): List<FeedbackTargetEntity> = targetMapper.selectList(
        KtQueryWrapper(FeedbackTargetEntity::class.java)
            .orderByAsc(FeedbackTargetEntity::sort)
            .orderByAsc(FeedbackTargetEntity::createTime)
    )

    override fun listTargetNames(): List<String> = allTargetsOrdered().map { it.name }

    override fun listTargets(): List<FeedbackTargetVO> = allTargetsOrdered().map { t ->
        FeedbackTargetVO(
            id = t.id,
            name = t.name,
            sort = t.sort,
            total = countBy { it.eq(FeedbackEntity::target, t.name) },
            unread = countBy { it.eq(FeedbackEntity::target, t.name).eq(FeedbackEntity::read, false) },
        )
    }

    override fun addTarget(name: String?): FeedbackTargetVO {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty()) throw CommonException(ErrorType.E102, "产品名不能为空")
        if (trimmed.length > 64) throw CommonException(ErrorType.E102, "产品名过长")
        val dup = targetMapper.selectCount(
            KtQueryWrapper(FeedbackTargetEntity::class.java).eq(FeedbackTargetEntity::name, trimmed)
        ) > 0
        if (dup) throw CommonException(ErrorType.E102, "该产品已存在")
        val maxSort = allTargetsOrdered().maxOfOrNull { it.sort } ?: -1
        val entity = FeedbackTargetEntity(name = trimmed, sort = maxSort + 1)
        targetMapper.insert(entity)
        return FeedbackTargetVO(id = entity.id, name = entity.name, sort = entity.sort)
    }

    override fun deleteTarget(id: String) {
        targetMapper.deleteById(id)
    }

    // ── 提交（公开） ─────────────────────────────────────────────────

    override fun submit(params: FeedbackSubmitParams, sourceIp: String?, userAgent: String?) {
        val target = params.target?.trim().orEmpty()
        val content = params.content?.trim().orEmpty()
        if (target.isEmpty()) throw CommonException(ErrorType.E102, "缺少所属产品")
        if (content.isEmpty()) throw CommonException(ErrorType.E102, "反馈内容不能为空")

        val allowed = listTargetNames()
        if (target !in allowed) {
            throw CommonException(ErrorType.E102, "未知的所属产品「$target」，可选值：${allowed.joinToString("、")}")
        }

        val email = params.email?.trim()?.takeIf { it.isNotEmpty() }
        if (email != null && (email.length > FeedbackEntity.MAX_EMAIL_LEN || !email.contains('@'))) {
            throw CommonException(ErrorType.E102, "邮箱格式不正确")
        }

        baseMapper.insert(
            FeedbackEntity(
                target = target,
                email = email,
                content = content.take(FeedbackEntity.MAX_CONTENT_LEN),
                sourceIp = sourceIp?.take(64),
                userAgent = userAgent?.take(500),
            )
        )
    }

    // ── 后台收件箱 ──────────────────────────────────────────────────

    override fun adminList(params: FeedbackSearchParams): IPage<FeedbackVO> =
        baseMapper.selectPage(params.toPage(), params.toWrapper()).convert { FeedbackVO(it) }

    override fun unreadCount(): Long = countBy { it.eq(FeedbackEntity::read, false) }

    override fun markRead(params: FeedbackBatchParams) {
        if (params.ids.isEmpty()) return
        val update = ktUpdate().`in`(FeedbackEntity::id, params.ids)
        if (params.read) {
            // read_time 只在第一次标记已读时写入，重复标记不覆盖
            update.set(FeedbackEntity::read, true)
                .setSql("read_time = coalesce(read_time, now())")
                .update()
        } else {
            update.set(FeedbackEntity::read, false).update()
        }
    }

    @Transactional
    override fun delete(ids: List<String>) {
        if (ids.isEmpty()) return
        removeByIds(ids)
    }

    private inline fun countBy(block: (KtQueryWrapper<FeedbackEntity>) -> KtQueryWrapper<FeedbackEntity>): Long =
        baseMapper.selectCount(block(KtQueryWrapper(FeedbackEntity::class.java)))

    companion object {
        val DEFAULT_TARGETS = listOf("Bookmarkify", "Vialite", "AgentTool")
    }
}
