package top.tcyeee.bookmarkify.server.config

import com.fasterxml.jackson.databind.ObjectMapper
import top.tcyeee.bookmarkify.entity.ConfigFieldChangeVO

/**
 * 两份配置 JSON 的逐字段差异。
 *
 * 一次保存提交的是**整份**配置，所以变更记录里直接摆原文的话，每一行看起来都像「什么都改了」；
 * 而人要问的是「这次动了哪一项」。
 *
 * 放在服务端算、且不认识任何具体配置类：对任意配置组都成立，不必每加一组就在后台再写一份比对。
 */
object ConfigDiff {

    /**
     * 顶层键取**并集**参与比较：新增字段（本次发版加的配置项）旧值为 null，删除字段新值为 null。
     * 两种都是真实发生过的变化，不该被悄悄吃掉。
     *
     * 解析失败返回空列表——调用方仍然会把两份原文下发，读的人不会因此什么都看不到。
     */
    fun of(old: String?, new: String, objectMapper: ObjectMapper): List<ConfigFieldChangeVO> = runCatching {
        val oldMap = old?.let { readMap(it, objectMapper) } ?: emptyMap()
        val newMap = readMap(new, objectMapper)
        (oldMap.keys + newMap.keys).mapNotNull { key ->
            val before = oldMap[key]
            val after = newMap[key]
            // 首次写入时 oldMap 为空，于是每一项都被列成一次变化——那正是那一行要表达的意思
            if (before == after) null
            else ConfigFieldChangeVO(field = key, oldValue = before?.toString(), newValue = after?.toString())
        }
    }.getOrDefault(emptyList())

    private fun readMap(json: String, objectMapper: ObjectMapper): Map<String, Any?> =
        objectMapper.readValue(json, Map::class.java).entries.associate { it.key.toString() to it.value }
}
