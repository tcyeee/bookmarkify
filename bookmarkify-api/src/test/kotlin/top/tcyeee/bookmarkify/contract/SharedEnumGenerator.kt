package top.tcyeee.bookmarkify.contract

import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter
import java.io.File
import java.nio.file.Path

/**
 * 从 Kotlin 枚举生成两端前端共用的 TypeScript 定义。
 *
 * ## 为什么要有这个东西
 *
 * 后端 48 个 VO 的形状，在 `bookmarkify-web/typing/` 与 `bookmarkify-admin/src/api/` 里各被
 * **手抄**了一份（约 45 + 110 个 interface）。其中最容易出事的是枚举：后端给一个枚举加一个
 * 取值，两个前端不会有任何提示 —— 它们各自的字符串联合类型里没有那一项，于是那个取值在界面上
 * 表现为空白、落到 `default` 分支、或者被一个 `switch` 静默丢掉。这类问题不报错、不崩溃，
 * 只是少显示了点东西，通常要等到用户问起才被发现。
 *
 * 项目里已经有做对了的先例：`contract/scrape-response.sample.json` 被 Rust 和 Kotlin 三个测试
 * 套件同时反序列化，任一侧改契约三处全红。那条边（API ↔ scrapper）的变更频率远低于这条边
 * （API ↔ 前端），却是唯一被钉住的一条。这个生成器把同一套办法搬过来。
 *
 * ## 为什么不是从 OpenAPI 生成
 *
 * Knife4j 的 spec 要**跑起来**才拿得到，CI 里为了生成几个枚举去起一个连着数据库和 Redis 的
 * Spring 上下文，代价和脆弱程度都不合适。枚举本身是编译期就完全确定的东西，直接反射编译产物
 * 更直接，也更快（这个测试跑在毫秒级）。VO 的 interface 将来若要接管，那时再考虑 spec 那条路。
 *
 * ## 生成物是**检入仓库**的
 *
 * 不是构建期产物。理由有两条：前端 CI 不跑 Gradle，拿不到生成步骤；而检入之后，
 * 后端加一个枚举取值会在 diff 里表现为"前端也跟着变了一行"，评审时一眼可见。
 * 一致性由 [SharedEnumContractTest] 守着，改了 Kotlin 却忘了重新生成就是红灯。
 */
object SharedEnumGenerator {

    /**
     * 不导出的枚举，附理由。
     *
     * 默认导出**全部**而不是白名单登记：漏登记的方向必须是安全的那一侧 —— 多导出一个前端用不到的
     * 枚举，代价是几行死代码；漏导出一个，代价是这个生成器对那个枚举根本不起作用，
     * 而那正是它要防的事。
     */
    private val EXCLUDED: Map<String, String> = mapOf(
        "FileType" to "带构造参数（体积上限/OSS 目录/mime 前缀），是服务端的上传策略，不过线",
        "AiCallScene" to "DeepSeek 调用场景的内部分类，只出现在 ai_call_log 的服务端写入侧",
        "OssAddressing" to "对象 key 的寻址策略，属于存储层实现细节",
        "OssObjectSource" to "对象账本的内部来源标记",
        "OssObjectState" to "对象账本的内部状态机（ACTIVE/ORPHAN/DELETED），对账链路专用",
    )

    /**
     * Kotlin 名 → 前端名。
     *
     * 只在两端**已经**用了另一个名字时才加映射：改名会波及所有引用处，而这个生成器的目的是
     * 消除漂移，不是顺手统一命名。空映射表示同名。
     */
    private val TS_NAME: Map<String, String> = mapOf(
        // 后台历来叫它 BookmarkParseStatus；Kotlin 侧的 `Enum` 后缀是个历史包袱，不值得为它改前端
        "ParseStatusEnum" to "BookmarkParseStatus",
        // 同一个枚举，后台在站点列表上叫 SiteLinkType，web 侧叫 BookmarkLinkType
        "BookmarkLinkType" to "BookmarkLinkType",
        "PageLockedField" to "BookmarkLockedField",
    )

    /** 生成物的落点。两份内容逐字相同，各自放在对应前端的源码树里（跨包引用在两套构建下都不成立）。 */
    val TARGETS: List<Pair<String, String>> = listOf(
        "bookmarkify-web" to "typing/enums.generated.ts",
        "bookmarkify-admin" to "src/api/enums.generated.ts",
    )

    data class ExportedEnum(val tsName: String, val kotlinName: String, val values: List<String>)

    /** 反射扫出 `entity/enums` 包下全部枚举，排除 [EXCLUDED]。 */
    fun collect(): List<ExportedEnum> {
        val scanner = object : ClassPathScanningCandidateComponentProvider(false) {
            // 默认实现只收「可独立实例化」的类，枚举与嵌套类会被它过滤掉
            override fun isCandidateComponent(beanDefinition: org.springframework.beans.factory.annotation.AnnotatedBeanDefinition) = true
        }.apply { addIncludeFilter(AssignableTypeFilter(Enum::class.java)) }

        return scanner.findCandidateComponents("top.tcyeee.bookmarkify.entity.enums")
            .mapNotNull { bd: BeanDefinition -> bd.beanClassName }
            .map { Class.forName(it) }
            .filter { it.isEnum }
            .filterNot { it.simpleName in EXCLUDED }
            .map { e -> ExportedEnum(TS_NAME[e.simpleName] ?: e.simpleName, e.simpleName, e.enumConstants.map { (it as Enum<*>).name }) }
            .sortedBy { it.tsName }
    }

    /** 渲染整个 `.ts` 文件的内容。 */
    fun render(): String = buildString {
        appendLine("// ⚠️ 本文件由 bookmarkify-api 的 SharedEnumGenerator 生成，**不要手工编辑**。")
        appendLine("//")
        appendLine("// 来源：bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/enums/")
        appendLine("// 重新生成：cd bookmarkify-api && ./gradlew generateSharedEnums")
        appendLine("// 一致性由 SharedEnumContractTest 守着 —— 改了 Kotlin 枚举却没重新生成，API 的测试会红。")
        appendLine("//")
        appendLine("// 形态是 `const 对象 + 同名类型`：值侧支持 `AssetRole.LOGO` 这种引用（web 侧的既有写法），")
        appendLine("// 类型侧等价于 `'FAVICON' | 'LOGO' | …` 的字符串联合（admin 侧的既有写法），两边都不用改调用点。")
        appendLine()
        collect().forEach { e ->
            appendLine("/** 对应 Kotlin `${e.kotlinName}` */")
            appendLine("export const ${e.tsName} = {")
            e.values.forEach { v -> appendLine("  $v: '$v',") }
            appendLine("} as const")
            appendLine("export type ${e.tsName} = (typeof ${e.tsName})[keyof typeof ${e.tsName}]")
            appendLine()
        }
    }.trimEnd() + "\n"

    /** 仓库根目录：从 API 模块的工作目录往上一层。 */
    fun repoRoot(): Path = File(System.getProperty("user.dir")).toPath().parent

    @JvmStatic
    fun main(args: Array<String>) {
        val content = render()
        TARGETS.forEach { (module, rel) ->
            val target = repoRoot().resolve(module).resolve(rel).toFile()
            target.parentFile.mkdirs()
            target.writeText(content)
            println("已生成 ${target.absolutePath} (${collect().size} 个枚举)")
        }
    }
}
