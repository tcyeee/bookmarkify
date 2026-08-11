package top.tcyeee.bookmarkify.server.repair

import com.baomidou.mybatisplus.annotation.TableName
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import top.tcyeee.bookmarkify.server.impl.OssReferrerRegistry
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

/**
 * 两份**手工清单**的一致性守卫。
 *
 * ## 为什么这个测试存在
 *
 * 这个库里没有外键约束（`deploy/schema.sql` 中只有 `background_config` 那两条），所以两件
 * 本该由数据库保证的事，全靠两段手写代码逐个列举：
 *
 * | 清单 | 漏登记的后果 | 可逆? |
 * |---|---|---|
 * | [OrphanCleanupService.OWNERSHIP_REGISTRY] | 页面/站点删掉后，附属行永远留在库里，没有任何入口能看见 | 是（脏数据，下次补删） |
 * | [OssReferrerRegistry.LEDGER_ID_FIELDS] | 还在使用的 OSS 对象被判成孤儿并**真的删掉** | **否** |
 *
 * 两者共同的性质是：**违反了没有任何症状**。不会抛异常，不会有慢查询，日志一行不报。
 * 唯一能发现它的时刻是有人恰好去数库里的行，或者用户报告图片裂了。
 *
 * 所以判据不能是「记得同步更新」这句注释——这个测试用反射把"库里到底有哪些归属列"这件事
 * 重新算一遍，与清单交叉核对。新增一张带 `page_id` / `site_id` 的表、或给某个实体加一个
 * `fileId` 字段时，它会在代码合并之前先红。
 *
 * ## 判据的形状
 *
 * 一律要求**显式登记**，包括"不该级联删"和"不算引用"这两种否定结论：从清单里省略掉一张表，
 * 就无法区分「想过了，确实不该动它」与「压根没想到有这张表」。前者是设计，后者是事故，
 * 而它们在代码里长得一模一样。
 */
@DisplayName("孤儿清理 / OSS 对账的两份清单必须覆盖库里全部归属列")
class RegistryCoverageTest {

    private companion object {
        const val ENTITY_PACKAGE = "top.tcyeee.bookmarkify.entity.entity"

        /**
         * 「这一行归属于某个页面/站点」的列名判据。
         *
         * 用后缀匹配而不是精确等于 `pageId`：`site_asset` 的归属列实际叫 `ownerId`，而它的
         * `pageId` 只是溯源列 —— 两种写法都必须被这个测试看见，否则一张只用 `ownerId` 表达
         * 归属的新表会从判据里整个漏掉。
         */
        val OWNERSHIP_SUFFIXES = listOf("pageId", "siteId", "ownerId")

        /** 持有 `oss_object.id` 的列名判据 */
        const val LEDGER_ID_SUFFIX = "fileId"

        /**
         * 扫描 `entity/entity` 下所有带 `@TableName` 的类。
         *
         * 走 Spring 的类路径扫描而不是硬编码一张类名表 —— 后者会和被测的那两份清单犯同一个
         * 错误（一份需要人记得更新的手工清单），那样这个测试就只是把问题挪了个地方。
         */
        fun scanEntities(): List<KClass<*>> {
            val scanner = ClassPathScanningCandidateComponentProvider(false).apply {
                addIncludeFilter(AnnotationTypeFilter(TableName::class.java))
            }
            return scanner.findCandidateComponents(ENTITY_PACKAGE)
                .mapNotNull { bd: BeanDefinition -> bd.beanClassName }
                .map { Class.forName(it).kotlin }
                .sortedBy { it.simpleName }
        }

        fun KClass<*>.propertiesEndingWith(vararg suffixes: String): Set<String> =
            memberProperties.map { it.name }
                .filter { name -> suffixes.any { name.endsWith(it, ignoreCase = false) } }
                .toSet()

        fun KClass<*>.tableName(): String =
            annotations.filterIsInstance<TableName>().firstOrNull()?.value ?: "?"
    }

    @Test
    @DisplayName("扫描本身要能找到实体——扫不到时全部断言会假绿")
    fun `entity scan finds the known tables`() {
        val entities = scanEntities()
        // 这个测试的每一条断言都是「集合 A ⊆ 集合 B」的形式，而空的 A 让它们全部平凡成立。
        // 扫描一旦因为包名改动、构建产物布局变化而失效，下面两条守卫就会在毫无察觉的情况下
        // 变成永远绿的装饰品 —— 所以先把扫描器自己钉住。
        assertThat(entities).hasSizeGreaterThan(20)
        assertThat(entities.map { it.tableName() })
            .contains("page", "site", "bookmark", "site_asset", "page_meta", "oss_object")
    }

    @Test
    @DisplayName("每一张按 page/site 归属的表都必须在 OWNERSHIP_REGISTRY 里有明确处置")
    fun `every page or site owned table is registered for cascade cleanup`() {
        val registry = OrphanCleanupService.OWNERSHIP_REGISTRY

        val owned = scanEntities().filter { it.propertiesEndingWith(*OWNERSHIP_SUFFIXES.toTypedArray()).isNotEmpty() }
        val missing = owned.filterNot { entity -> registry.keys.any { it == entity } }

        assertThat(missing)
            .describedAs(
                """
                这些实体带有 page/site 归属列，却没有在 OrphanCleanupService.OWNERSHIP_REGISTRY 里登记：
                ${missing.joinToString("\n") { "  - ${it.simpleName} (表 ${it.tableName()}, 列 ${it.propertiesEndingWith(*OWNERSHIP_SUFFIXES.toTypedArray())})" }}

                请在那张表里补一条，二选一：
                  · Disposition.Cascade  —— 同时在 run() 里补上对应的 purge 调用
                  · Disposition.Retained("为什么它不该被级联删除")
                注意 Retained 也必须登记：省略掉它，就分不清"想过了"和"没想到"。
                """.trimIndent()
            )
            .isEmpty()
    }

    @Test
    @DisplayName("OWNERSHIP_REGISTRY 里不该有已经不存在的实体")
    fun `ownership registry has no stale entries`() {
        val existing = scanEntities().toSet()
        val stale = OrphanCleanupService.OWNERSHIP_REGISTRY.keys.filterNot { it in existing }
        // 反方向同样要守：一条指向已删实体的登记会让清单看起来比实际覆盖得更全，
        // 而"看起来已经覆盖了"正是这类漏删最常见的成因
        assertThat(stale)
            .describedAs("这些登记项对应的实体已不存在，请从 OWNERSHIP_REGISTRY 移除: $stale")
            .isEmpty()
    }

    @Test
    @DisplayName("每一个持有 oss_object 引用的字段都必须在 OssReferrerRegistry 里登记")
    fun `every ledger reference field is registered for reconciliation`() {
        val declared = OssReferrerRegistry.LEDGER_ID_FIELDS
        val excluded = OssReferrerRegistry.EXCLUDED

        val gaps = buildList {
            scanEntities().forEach { entity ->
                val actual = entity.propertiesEndingWith(LEDGER_ID_SUFFIX)
                if (actual.isEmpty()) return@forEach
                val known = (declared[entity] ?: emptySet()) + (excluded[entity]?.keys ?: emptySet())
                (actual - known).forEach { add("${entity.simpleName}.$it (表 ${entity.tableName()})") }
            }
        }

        assertThat(gaps)
            .describedAs(
                """
                这些字段持有 oss_object 引用，却没有在 OssReferrerRegistry 里登记：
                ${gaps.joinToString("\n") { "  - $it" }}

                漏登记的后果是**不可逆的**：collectReferencedKeys 看不到这个引用 → 对象被判成孤儿
                → reclaim-orphans 打开时在宽限期后真的从桶里删掉，用户侧表现为图片突然裂掉。
                请二选一：
                  · 加进 LEDGER_ID_FIELDS，并在 collectReferencedKeys 里补上对应的查询
                  · 加进 EXCLUDED 并写明为什么它不算一次引用
                """.trimIndent()
            )
            .isEmpty()
    }

    @Test
    @DisplayName("OssReferrerRegistry 登记的字段必须真的存在于实体上")
    fun `ledger registry has no stale fields`() {
        val stale = buildList {
            (OssReferrerRegistry.LEDGER_ID_FIELDS + OssReferrerRegistry.EXCLUDED.mapValues { it.value.keys })
                .forEach { (entity, fields) ->
                    val actual = entity.memberProperties.map { it.name }.toSet()
                    (fields - actual).forEach { add("${entity.simpleName}.$it") }
                }
        }
        // 字段被改名时，登记项会静默失效——它不再对应任何真实字段，于是那个引用重新变得不可见，
        // 而清单看起来仍然是全的。这一条把改名也纳入守卫范围
        assertThat(stale)
            .describedAs("这些登记的字段在实体上已不存在（多半是改名了），请同步更新 OssReferrerRegistry: $stale")
            .isEmpty()
    }
}
