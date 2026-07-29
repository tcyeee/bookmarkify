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

    override fun updateConfig(activeCheckIntervalHours: Int, abnormalCheckIntervalHours: Int): BookmarkLivenessConfigValue {
        if (activeCheckIntervalHours < 1 || abnormalCheckIntervalHours < 1) {
            throw CommonException(ErrorType.E102, "检测频率必须大于等于 1 小时")
        }
        // 异常书签理应比已激活书签查得更勤：前端只挡了各自 >=1 的下限，
        // 两者的大小关系必须在这里做唯一的服务端校验，否则管理员可能配出「异常书签反而查得更少」的语义倒挂。
        if (abnormalCheckIntervalHours > activeCheckIntervalHours) {
            throw CommonException(ErrorType.E102, "异常书签检测频率不能低于已激活书签检测频率")
        }
        val value = BookmarkLivenessConfigValue(activeCheckIntervalHours, abnormalCheckIntervalHours)
        systemConfigService.setValue(CONFIG_KEY, objectMapper.writeValueAsString(value))
        return value
    }

    companion object {
        private const val CONFIG_KEY = "bookmark_liveness_check_frequency"
    }
}
