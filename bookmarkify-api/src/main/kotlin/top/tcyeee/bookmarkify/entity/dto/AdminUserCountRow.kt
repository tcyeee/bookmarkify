package top.tcyeee.bookmarkify.entity.dto

/** 用户管理页批量统计的一行结果。字段顺序需与 mapper 的 SELECT 顺序一致。 */
data class AdminUserCountRow(
    val uid: String,
    val count: Long,
)
