package top.tcyeee.bookmarkify.server.impl

import cn.hutool.json.JSONUtil
import org.springframework.stereotype.Service
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
        val value = BookmarkLivenessConfigValue(activeCheckIntervalHours, abnormalCheckIntervalHours)
        systemConfigService.setValue(CONFIG_KEY, JSONUtil.toJsonStr(value))
        return value
    }

    companion object {
        private const val CONFIG_KEY = "bookmark_liveness_check_frequency"
    }
}
