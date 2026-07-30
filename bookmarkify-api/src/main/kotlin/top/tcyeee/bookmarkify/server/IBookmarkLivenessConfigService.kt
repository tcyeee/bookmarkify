package top.tcyeee.bookmarkify.server

import top.tcyeee.bookmarkify.entity.dto.BookmarkLivenessConfigValue

interface IBookmarkLivenessConfigService {
    /** 查询全局书签活性检查频率配置，不存在时返回默认值 */
    fun getConfig(): BookmarkLivenessConfigValue

    /** 更新全局书签巡检配置 */
    fun updateConfig(
        activeCheckIntervalHours: Int,
        abnormalCheckIntervalHours: Int,
        contentRefreshIntervalDays: Int,
    ): BookmarkLivenessConfigValue
}
