package top.tcyeee.bookmarkify.server.impl

import cn.hutool.json.JSONUtil
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.dto.BookmarkLivenessConfigValue
import top.tcyeee.bookmarkify.server.IBookmarkLivenessConfigService
import top.tcyeee.bookmarkify.server.ISystemConfigService

@Service
class BookmarkLivenessConfigServiceImpl(
    private val systemConfigService: ISystemConfigService,
) : IBookmarkLivenessConfigService {

    override fun getConfig(): BookmarkLivenessConfigValue =
        systemConfigService.getValue(CONFIG_KEY)?.let { JSONUtil.toBean(it, BookmarkLivenessConfigValue::class.java) }
            ?: BookmarkLivenessConfigValue()

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
        systemConfigService.setValue(CONFIG_KEY, JSONUtil.toJsonStr(value))
        return value
    }

    companion object {
        private const val CONFIG_KEY = "bookmark_liveness_check_frequency"
    }
}
