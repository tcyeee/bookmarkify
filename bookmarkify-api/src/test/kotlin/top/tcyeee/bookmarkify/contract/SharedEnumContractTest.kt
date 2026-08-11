package top.tcyeee.bookmarkify.contract

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 后端枚举与两端前端的 TS 定义必须逐字一致。
 *
 * ## 这个测试防的是什么
 *
 * 后端给一个枚举加一个取值 —— 比如 `ShareStatus` 加上 `REVIEW_REJECTED` —— 而两个前端各自维护着
 * 一份手抄的字符串联合类型。它们不会报错，`tsc` 也不会：多出来的那个值只是不在联合里，于是它
 * 落到某个 `switch` 的 `default`、映射表里查不到、界面上显示为空白。**这类问题没有任何症状**，
 * 通常要等到有用户问"为什么这条分享状态是空的"才会被发现。
 *
 * 这与 `ScrapeContractTest` 守 API ↔ scrapper 那条边是同一件事。区别只在于那条边有一份共享
 * fixture，而这条边此前什么都没有 —— 尽管它的变更频率高一个数量级。
 *
 * ## 失败了怎么办
 *
 * `cd bookmarkify-api && ./gradlew generateSharedEnums`，然后把两个前端里改动的文件一起提交。
 */
@DisplayName("Kotlin 枚举 ↔ 前端 TS 定义")
class SharedEnumContractTest {

    @Test
    @DisplayName("两端检入的 enums.generated.ts 必须与当前 Kotlin 枚举一致")
    fun `checked in typescript enums match the kotlin source`() {
        val expected = SharedEnumGenerator.render()
        val root = SharedEnumGenerator.repoRoot()

        SharedEnumGenerator.TARGETS.forEach { (module, rel) ->
            val file = root.resolve(module).resolve(rel).toFile()
            // 只在两个前端目录都在场时断言：这个仓库是四个服务同仓，但单独 checkout API
            // 子目录做构建是可能的，那种情况下没有可比对的对象，跳过而不是假红
            assumeTrue(root.resolve(module).toFile().isDirectory, "$module 不在工作区，跳过")

            assertThat(file)
                .describedAs("$module/$rel 不存在 —— 跑 ./gradlew generateSharedEnums 生成它")
                .exists()
            assertThat(file.readText())
                .describedAs(
                    """
                    $module/$rel 与 Kotlin 枚举已经不一致。

                    多半是后端加/改/删了某个枚举取值，而前端那份没跟上 —— 这正是本测试存在的原因：
                    不一致在两个前端里都不会报错，只会让那个取值在界面上静默消失。

                    修复：cd bookmarkify-api && ./gradlew generateSharedEnums
                    然后把两个前端里变动的文件一并提交。
                    """.trimIndent()
                )
                .isEqualTo(expected)
        }
    }

    @Test
    @DisplayName("生成器本身要真的产出内容——空产物会让上面那条断言假绿")
    fun `generator produces a non trivial payload`() {
        val enums = SharedEnumGenerator.collect()
        // 扫描一旦因为包名改动、构建产物布局变化而失效，collect() 返回空表，render() 就只剩文件头，
        // 而"两边都只有文件头"照样相等 —— 上面那条断言会在毫无察觉的情况下变成永远绿的装饰品
        assertThat(enums).hasSizeGreaterThan(8)
        assertThat(enums.map { it.tsName })
            .contains("AssetRole", "DisplayMode", "PingOutcome", "BookmarkParseStatus", "ShareStatus")
        assertThat(enums.first { it.tsName == "PingOutcome" }.values)
            .containsExactly("ALIVE", "DEAD", "UNKNOWN")
    }
}
