package top.tcyeee.bookmarkify.entity.entity

import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/** 书签分类字典（受控词表，预先 seed） */
@TableName("category")
data class Category(
    @TableId var id: String,
    @field:Schema(description = "稳定 slug，喂给 DeepSeek/未来筛选") var slug: String,
    @field:Schema(description = "分类中文展示名") var name: String,
    @field:Schema(description = "给 DeepSeek 的判定说明") var description: String? = null,
    @field:Schema(description = "预留 UI 颜色") var color: String? = null,
    @field:Schema(description = "展示顺序") var sort: Int = 0,

    @JsonIgnore @field:Schema(description = "是否删除") var deleted: Boolean = false,
    @JsonIgnore @field:Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @field:Schema(description = "更新时间") var lastModified: LocalDateTime = LocalDateTime.now(),
)
