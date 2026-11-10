package top.tcyeee.bookmarkify.config.audit

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import top.tcyeee.bookmarkify.entity.entity.AdminActionLogEntity

/**
 * 把后台接口的入参拼成一段可入库的 JSON 摘要，**并在此之前打掉凭据**。
 *
 * ## 为什么单独一个纯对象
 *
 * 这不是"以防万一"的洁癖：后台登录接口的入参就是 `AdminLoginParams(account, password)`。
 * 不打码，`admin_action_log` ——一张保留两年的审计表—— 第一行就是管理员密码，等于用一个
 * 记录"谁动了什么"的机制去泄漏凭据，比没有审计更糟。Google / GitHub 登录那条路带的
 * `idToken` / `code` 同理。
 *
 * 做成不依赖 Spring 的纯函数，是因为它是一道**安全判据**，必须能被单测钉住
 * （见 `AuditParamRedactorTest`）—— 切面本身要 AOP 上下文才跑得起来，而"密码有没有漏出去"
 * 这件事不该依赖谁去手点一次后台登录才能验证。
 */
object AuditParamRedactor {

    /**
     * 字段名**包含**这些片段即打码。
     *
     * 用包含而不是精确匹配：真实字段名有 `password` / `pwd` / `idToken` / `clientSecret` /
     * `authToken` 等等变体，精确清单必然漏，而在审计摘要里多打掉一个无害字段的代价约等于零 ——
     * 这是一个刻意偏向"宁可多打"的判据。
     */
    private val SENSITIVE_FIELDS = listOf("password", "pwd", "secret", "token", "credential", "captcha")

    private val MASK: JsonNode = TextNode.valueOf("***")

    /**
     * @param names 形参名（编译期带 `-java-parameters` 才有值，拿不到时退化成 `arg0`）
     * @param values 对应的实参，已由调用方剔除 `MultipartFile` / servlet 这类不可序列化的东西
     * @return JSON 摘要；没有可记录的参数时为 null（不是 `"{}"`，空对象在列表里是纯噪音）
     */
    fun summarize(names: List<String?>, values: List<Any?>, mapper: ObjectMapper): String? = runCatching {
        val payload = values.withIndex().associate { (i, v) ->
            val name = names.getOrNull(i) ?: "arg$i"
            val node: JsonNode = runCatching { mapper.valueToTree<JsonNode>(v) }
                // 序列化不了的参数退回类名：一次整体序列化会因为其中一个参数而丢掉全部现场
                .getOrElse { mapper.valueToTree(v?.javaClass?.simpleName) }
            name to if (isSensitive(name)) MASK else redact(node)
        }
        if (payload.isEmpty()) null
        else mapper.writeValueAsString(payload).take(AdminActionLogEntity.MAX_PARAMS_LEN)
    }.getOrNull()

    /** 递归把敏感字段换成 `***`，就地修改并返回同一棵树 */
    fun redact(node: JsonNode): JsonNode {
        when (node) {
            is ObjectNode -> node.fieldNames().asSequence().toList().forEach { field ->
                if (isSensitive(field)) node.set<JsonNode>(field, MASK)
                else node.set<JsonNode>(field, redact(node.get(field)))
            }
            is ArrayNode -> for (i in 0 until node.size()) node.set(i, redact(node.get(i)))
            else -> Unit
        }
        return node
    }

    fun isSensitive(name: String): Boolean = SENSITIVE_FIELDS.any { name.contains(it, ignoreCase = true) }
}
