package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.enums.OssAddressing
import top.tcyeee.bookmarkify.entity.enums.OssObjectSource
import top.tcyeee.bookmarkify.entity.enums.OssObjectState
import top.tcyeee.bookmarkify.utils.CurrentEnvironment
import top.tcyeee.bookmarkify.utils.OssUtils
import top.tcyeee.bookmarkify.utils.currentEnvironment
import java.time.LocalDateTime

/**
 * OSS 里的一个对象。**一份字节一行，无属主。**
 *
 * 整改计划见根目录 `FILE-SYSTEM-REFACTOR.md`。这里只重复三条最容易改错的：
 *
 * **1. 主键是 UUID，不是 [contentHash]。** 去重能力来自 `content_hash` 上的索引，与主键选谁
 * 无关；用 hash 当主键会让全部引用方与"内容寻址"这个决策强耦合，将来换哈希算法、加盐、改
 * `sha256:` 前缀格式，引用方就得跟着重写一遍 —— 那正是引入 file_id 间接层要消灭的东西。
 *
 * **2. 没有属主列。** 去重意味着同一份字节只有一行，两个用户传同一张图时"这行的 uid 填谁"
 * 无解。属主、原始文件名、上传时间这些**每次引用各不相同**的信息属于引用方，不属于这里。
 *
 * **3. 这张表不是图片的唯一来源。** `site_asset` 里有大量资产根本没落对象存储（只做了 PROBE、
 * 或指向外站直连），加上改造前写入的完整 URL 存量，展示层永远要处理多种形态 ——
 * `OssUtils.signAsset` 的分流不会因为这张表的存在而消失。
 */
@TableName("oss_object")
data class OssObjectEntity(
    @TableId var id: String = IdUtil.fastUUID(),

    /** 完整 object key，不含域名、不含 query。域名与签名是 API 的部署策略，不进库 */
    @field:Schema(description = "OSS object key") var objectKey: String = "",

    /**
     * 图片字节的 sha256，形如 `sha256:<hex>`，由 scrapper 计算并回报。
     *
     * 当前 key 还是源 URL 哈希，所以**同一个 hash 完全可能出现在多行**（同一张图挂在不同 URL 下）
     * —— 这正是要做去重的原因，也是这一列上的索引现阶段不能是 UNIQUE 的原因。
     */
    @field:Schema(description = "字节 sha256") var contentHash: String? = null,

    @field:Schema(description = "key 的推导方式") var addressing: OssAddressing = OssAddressing.LEGACY,
    @field:Schema(description = "写入方") var source: OssObjectSource = OssObjectSource.SCRAPPER,

    @field:Schema(description = "字节数") var size: Long? = null,
    @field:Schema(description = "MIME") var mime: String? = null,
    @field:Schema(description = "像素宽") var width: Int? = null,
    @field:Schema(description = "像素高") var height: Int? = null,
    @field:Schema(description = "是否矢量图") var isVector: Boolean = false,

    @field:Schema(description = "写入时所处环境") var environment: CurrentEnvironment = currentEnvironment(),
    @field:Schema(description = "入账时间") var createTime: LocalDateTime = LocalDateTime.now(),

    /** 对账任务写：本轮 ListObjects 在桶里确认到它还在 */
    @field:Schema(description = "最近一次在桶里被确认存在的时间") var lastSeenAt: LocalDateTime? = null,

    /** 对账任务写：本轮扫描引用方表时还有人指向它 */
    @field:Schema(description = "最近一次被引用的时间") var lastRefAt: LocalDateTime? = null,

    /** 只由对账任务改写。业务写入方一律 ACTIVE —— "还有没有人要"不是写入方能回答的问题 */
    @field:Schema(description = "对账结论") var state: OssObjectState = OssObjectState.ACTIVE,
) {
    /**
     * key 去掉目录和扩展名后剩下的那一段。
     *
     * 存在的唯一理由是**兼容既有接口契约**：上传头像/背景图的接口一直返回旧 `user_file.current_name`
     * （即这一段 UUID），前端拿它做背景图的选中标识。`user_file` 退场后这个值必须逐字节保持不变，
     * 否则前端会认不出自己刚传的图。新代码不要依赖它 —— 用 [id]。
     */
    val currentName: String get() = objectKey.substringAfterLast('/').substringBeforeLast('.')

    /**
     * 字节永不改变 —— 决定签发多长的有效期，进而决定浏览器与缓存代理能不能真的缓存住它。
     *
     * **判据是"这个 key 会不会被原地覆盖写"，不是"是不是内容寻址"。** 两者不等价，
     * [OssAddressing.RANDOM] 就卡在这个缝里：它的 key 不由字节推导，但用户换一次头像就是
     * 一个新 UUID、一个新 key，旧 key 的那份字节同样永不改变。判成 false 的代价是头像和
     * 背景图白白吃 [OssUtils.DEFAULT_TTL_MILLIS] 的短有效期，URL 每小时换一次、每小时全量
     * 回源一次，而每次回源都要付一次 GET 请求费和一次图片处理费。
     *
     * 真正会自我覆盖的只有 [OssAddressing.SOURCE_URL]（截图刻意保留这种寻址以让存储量有
     * 上界），以及推导方式不可考的 [OssAddressing.LEGACY]。
     *
     * 写成穷尽的 `when` 而不是 `!= SOURCE_URL`：新增一种寻址方式时这里会**编译失败**，逼人
     * 当场回答"它会不会被覆盖"。用黑名单的话新值默认落进 true，等于给一个可能被覆盖的 key
     * 签发 24h 长效链接 —— 症状是用户改完头像后一整天还看到旧图，且没有任何报错可循。
     */
    val immutable: Boolean get() = when (addressing) {
        OssAddressing.CONTENT, OssAddressing.RANDOM -> true
        OssAddressing.SOURCE_URL, OssAddressing.LEGACY -> false
    }

    /** 前端可直接用的限时签名地址；能否缩放由 signAsset 按 mime 判定（SVG/ICO 走原图直出） */
    fun signedUrl(size: Int): String? =
        OssUtils.signAsset(objectKey, size, immutable, mime = mime, isVector = isVector)
}
