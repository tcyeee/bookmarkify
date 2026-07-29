package top.tcyeee.bookmarkify.entity.dto

/** 存入 system_config 的书签活性检查频率配置值(小时) */
data class BookmarkLivenessConfigValue(
    val activeCheckIntervalHours: Int = 168,
    val abnormalCheckIntervalHours: Int = 24,
)
