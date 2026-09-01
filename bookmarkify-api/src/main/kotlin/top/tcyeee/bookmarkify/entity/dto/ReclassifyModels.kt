package top.tcyeee.bookmarkify.entity.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * 首页「重新归类」链路的内部/传输模型。
 *
 * 语义上与 [CategoryModels] 里的分类字典是两回事：那套按 canonical page 维度、写受控词表；
 * 这套是**用户桌面布局**维度的一次性重排 —— 目标是用户自己的 `user_layout_node` 文件夹，
 * 不落任何字典，方案由前端确认后原样回传。
 */

/** 喂给 DeepSeek 的单条书签信息（越少越省 token） */
data class ReclassifyItem(
    val layoutNodeId: String,
    val title: String?,
    val description: String?,
    val host: String?,
)

/** 归类方案里的一组：一个目标文件夹 + 落入其中的书签节点 */
data class ReclassifyGroup(
    @field:Schema(description = "目标文件夹名称") val folderName: String,
    @field:Schema(description = "该文件夹当前不存在，确认后需新建") val isNew: Boolean,
    @field:Schema(description = "已存在时的目标文件夹节点ID；isNew 时为 null") val folderId: String?,
    @field:Schema(description = "该组内书签的布局节点ID列表") val nodeIds: List<String>,
    @field:Schema(description = "这一组就是「保留在原文件夹」，确认时不产生移动") val keep: Boolean = false,
)

/** AI 归类方案（不落库，前端确认后原样回传其中被勾选的组） */
data class ReclassifyPlan(
    @field:Schema(description = "被归类的源文件夹节点ID") val sourceFolderId: String,
    @field:Schema(description = "源文件夹名称") val sourceFolderName: String,
    @field:Schema(description = "归类分组（按书签数降序）") val groups: List<ReclassifyGroup>,
)
