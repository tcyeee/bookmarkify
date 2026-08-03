package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

/** canonical 书签 ↔ 分类 关联 */
@TableName("page_category")
data class PageCategory(
    @TableId var id: String = IdUtil.fastUUID(),
    @field:Schema(description = "canonical 书签ID") var pageId: String,
    @field:Schema(description = "分类ID(category.id)") var categoryId: String,
    @field:Schema(description = "来源") var source: CategorySource = CategorySource.DEEPSEEK,

    @JsonIgnore @field:Schema(description = "创建时间") var createTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @field:Schema(description = "是否删除") var deleted: Boolean = false,
) {
    constructor(pageId: String, categoryId: String) : this(
        id = IdUtil.fastUUID(), pageId = pageId, categoryId = categoryId,
    )
}

/** 分类关联的来源 */
enum class CategorySource {
    /* DeepSeek 自动推断 */
    DEEPSEEK,

    /* 管理员/用户手动指定 */
    MANUAL,
}
