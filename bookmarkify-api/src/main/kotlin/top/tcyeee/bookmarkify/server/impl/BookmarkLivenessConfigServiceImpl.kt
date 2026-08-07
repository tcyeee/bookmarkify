package top.tcyeee.bookmarkify.server.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.config.log
import top.tcyeee.bookmarkify.entity.dto.BookmarkLivenessConfigValue
import top.tcyeee.bookmarkify.server.IBookmarkLivenessConfigService
import top.tcyeee.bookmarkify.server.ISystemConfigService
import top.tcyeee.bookmarkify.server.liveness.LivenessPolicy

@Service
class BookmarkLivenessConfigServiceImpl(
    private val systemConfigService: ISystemConfigService,
    // Hutool 的 JSONUtil.toBean 走 JavaBean 反射(无参构造 + setter)，
    // 而这里的配置值是 Kotlin 全 val 的 data class，两者都没有，必须用带 kotlin module 的 Jackson。
    private val objectMapper: ObjectMapper,
) : IBookmarkLivenessConfigService {

    override fun getConfig(): BookmarkLivenessConfigValue {
        val raw = systemConfigService.getValue(CONFIG_KEY) ?: return BookmarkLivenessConfigValue()
        return runCatching { objectMapper.readValue<BookmarkLivenessConfigValue>(raw) }
            .getOrElse {
                // 配置读坏不该让整个管理页 500，退回默认值即可，管理员重新保存一次就能修正。
                log.warn("[getConfig] 解析书签活性检查配置失败, 使用默认值: raw=$raw, err=${it.message}")
                BookmarkLivenessConfigValue()
            }
    }

    override fun updateConfig(value: BookmarkLivenessConfigValue): BookmarkLivenessConfigValue {
        if (value.activeCheckIntervalHours < 1 || value.abnormalCheckIntervalHours < 1) {
            throw CommonException(ErrorType.E102, "检测频率必须大于等于 1 小时")
        }
        // 异常书签理应比已激活书签查得更勤：前端只挡了各自 >=1 的下限，
        // 两者的大小关系必须在这里做唯一的服务端校验，否则管理员可能配出「异常书签反而查得更少」的语义倒挂。
        if (value.abnormalCheckIntervalHours > value.activeCheckIntervalHours) {
            throw CommonException(ErrorType.E102, "异常书签重试间隔不能低于已激活书签检测频率")
        }
        // 倍数取 1 表示不退避（固定间隔），是个合法配置；0 和负数没有语义。
        // 上不封顶交给「最长间隔」去兜，那才是真正决定曲线终点的那个数。
        if (value.abnormalBackoffMultiplier < 1) {
            throw CommonException(ErrorType.E102, "重试叠加倍数必须大于等于 1")
        }
        // 上限小于基数的话，第一次重试就已经越过上限，整条曲线塌成固定间隔——
        // 策略侧对此有兜底（取基数），但那是防御，不是可以配出来的合法状态。
        if (value.abnormalMaxIntervalHours < value.abnormalCheckIntervalHours) {
            throw CommonException(ErrorType.E102, "最长重试间隔不能短于初次重试间隔")
        }
        // 1 表示「探一次不通就判失活」，是退回旧行为，允许。
        // 上界必须小于归档阈值：配得比它还大，记录会在判失活之前先被归档，
        // 于是「失活」这个状态永远不会出现，而失活书签重试巡检正是按它选候选的
        if (value.deadConfirmFailures < 1 || value.deadConfirmFailures >= LivenessPolicy.ARCHIVE_AFTER_FAILURES) {
            throw CommonException(
                ErrorType.E102,
                "判定失活的连续失败次数必须在 1 ~ ${LivenessPolicy.ARCHIVE_AFTER_FAILURES - 1} 之间"
            )
        }
        // 重新抓取一次的代价远高于一次 HEAD 探测（可能拉起无头浏览器、下载图片、传 OSS），
        // 配得比活性探测还勤就失去了分档的意义，且会成倍放大抓取压力。
        if (value.contentRefreshIntervalDays < 1) {
            throw CommonException(ErrorType.E102, "内容重新抓取间隔必须大于等于 1 天")
        }
        if (value.contentRefreshIntervalDays * 24 < value.activeCheckIntervalHours) {
            throw CommonException(ErrorType.E102, "内容重新抓取间隔不能短于已激活书签检测频率")
        }
        systemConfigService.setValue(CONFIG_KEY, objectMapper.writeValueAsString(value))
        return value
    }

    companion object {
        private const val CONFIG_KEY = "bookmark_liveness_check_frequency"
    }
}
