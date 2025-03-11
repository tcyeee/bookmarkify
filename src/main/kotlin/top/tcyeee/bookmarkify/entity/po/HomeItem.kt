package top.tcyeee.bookmarkify.entity.po

import com.baomidou.mybatisplus.annotation.TableField
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Max
import top.tcyeee.bookmarkify.entity.enums.HomeFunctionEnum
import top.tcyeee.bookmarkify.entity.enums.HomeItemTypeEnum
import top.tcyeee.bookmarkify.entity.json.BookmarkDir

/**
 * 主页显示的文件夹/APP/设置
 *
 * @author tcyeee
 * @date 4/14/24 15:10
 */
@TableName("home_item")
data class HomeItem(
    @TableId var id: String,
    @Max(40) @Schema(description = "用户ID") var uid: String?,
    @Schema(description = "序号") var sort: Int,
    @Schema(description = "书签类型") var type: HomeItemTypeEnum,

    /* 三选一 */
    @TableField(exist = false) @Schema(description = "单独书签信息") var bookmark: Bookmark,
    @TableField(exist = false) @Schema(description = "书签组信息") var bookmarkDir: BookmarkDir,
    @TableField(exist = false) @Schema(description = "系统功能入口") var functionEnum: HomeFunctionEnum,

    @JsonIgnore @Max(40) @Schema(hidden = true) var bookmarkUserLinkId: String,
    @JsonIgnore @Schema(hidden = true) var bookmarkDirJson: String,
    @JsonIgnore @Schema(hidden = true) var functionId: Int,
    @JsonIgnore @Schema(hidden = true) var deleted: Boolean = false,
)
