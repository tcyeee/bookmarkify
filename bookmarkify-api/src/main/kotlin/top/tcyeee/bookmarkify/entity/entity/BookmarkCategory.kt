package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/** 网站(canonical bookmark) ↔ 分类 关联 */
@TableName("bookmark_category")
data class BookmarkCategory(
    @TableId var id: String = IdUtil.fastUUID(),
    @field:Schema(description = "canonical 书签ID") var bookmarkId: String,
    @field:Schema(description = "分类ID(website_category.id)") var categoryId: String,
    @field:Schema(description = "来源") var source: String = "DEEPSEEK",

    @JsonIgnore @field:Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @field:Schema(description = "是否删除") var deleted: Boolean = false,
) {
    constructor(bookmarkId: String, categoryId: String) : this(
        id = IdUtil.fastUUID(), bookmarkId = bookmarkId, categoryId = categoryId,
    )
}
