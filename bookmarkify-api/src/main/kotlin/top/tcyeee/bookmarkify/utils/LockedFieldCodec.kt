package top.tcyeee.bookmarkify.utils

/**
 * 「管理员手工锁定的字段」在库里的编解码：逗号分隔的枚举名。
 *
 * 抽出来是因为 `bookmark` 与 `site` 两层各有自己的锁定字段集合（前者 TITLE/DESCRIPTION，
 * 后者 BRAND_NAME/SHORT_NAME），但两处的编解码规则必须逐字一致 —— 尤其是下面这条：
 *
 * > **空集合存 NULL 而不是空字符串**，省得查询和判空要同时处理两种"没有锁"的表示。
 *
 * 各写一遍迟早会分叉成一处存 `''` 一处存 `NULL`，然后 `locked_fields IS NULL` 这类条件
 * 就会漏掉一半的行。
 */
object LockedFieldCodec {

    /** 解析。无法识别的取值直接忽略 —— 别让一行脏数据把整条记录的解析拖崩。 */
    inline fun <reified E : Enum<E>> decode(raw: String?): Set<E> =
        raw?.split(',')
            ?.mapNotNull { token -> enumValues<E>().firstOrNull { it.name == token.trim() } }
            ?.toSet()
            ?: emptySet()

    /** 序列化。空集合返回 null，见类注释。 */
    fun <E : Enum<E>> encode(fields: Set<E>): String? =
        fields.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name }
}
