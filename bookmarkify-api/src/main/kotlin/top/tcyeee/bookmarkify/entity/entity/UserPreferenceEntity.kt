package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@TableName("user_preference")
data class UserPreferenceEntity(
    @TableId @field:Schema(description = "主键ID") var id: String = IdUtil.fastUUID(),
    @field:Schema(description = "用户ID") var uid: String,

    @field:Schema(description = "主页背景配置ID") var backgroundConfigId: String? = null,
    @field:Schema(description = "书签打开方式") var bookmarkOpenMode: BookmarkOpenMode = BookmarkOpenMode.CURRENT_TAB,
    @field:Schema(description = "是否开启极简模式") var minimalMode: Boolean = false,
    @field:Schema(description = "书签排列方式") var bookmarkLayout: BookmarkLayoutMode = BookmarkLayoutMode.DEFAULT,
    @field:Schema(description = "是否显示标题") var showTitle: Boolean = true,
    @field:Schema(description = "翻页方式") var pageMode: PageTurnMode = PageTurnMode.VERTICAL_SCROLL,

    @field:Schema(description = "更新时间") var updateTime: LocalDateTime = LocalDateTime.now(),
    @field:Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
)


/**
 * 用户系统偏好设置
 *
 * 1. 主页背景
 * 2. 书签打开方式(当前/新页面)
 * 3. 极简模式开关
 * 4. 书签排列方式(紧凑/默认/空旷)
 * 5. 是否显示标题(默认显示)
 * 6. 翻页方式(垂直滚动/横向翻页)
 */
enum class BookmarkOpenMode { CURRENT_TAB, NEW_TAB }
enum class BookmarkLayoutMode { COMPACT, DEFAULT, SPACIOUS }
enum class PageTurnMode { VERTICAL_SCROLL, HORIZONTAL_PAGE }