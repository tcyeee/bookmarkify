package top.tcyeee.bookmarkify.entity.entity

import cn.hutool.core.util.IdUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.baomidou.mybatisplus.annotation.TableName
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size
import top.tcyeee.bookmarkify.entity.enums.BookmarkLinkType
import top.tcyeee.bookmarkify.utils.ChromeBookmarkRawData
import top.tcyeee.bookmarkify.utils.WebsiteParser
import java.time.LocalDateTime

/**
 * 用户的一次收藏 —— **用户嘴里的「书签」就是这一层**。
 *
 * 三层分界：[SiteEntity] 一域名一行，[PageEntity] 一个规范化页面一行，本类一次收藏一行。
 * 本表原名 `bookmark_user_link`，而当时 `bookmark` 指的是页面，于是列名读起来像
 * 「书签的书签ID」，也让人误以为 bookmark 与 site 是同一个概念（实测 www.bilibili.com
 * 一个 site 下挂着 4 个 page：首页 + 3 个视频）。2026-08-03 三层正名后名实相符。
 */
@TableName("bookmark")
data class BookmarkEntity(
    @TableId val id: String = IdUtil.fastUUID(),
    @field:Size(max = 40) @field:Schema(description = "用户ID") var uid: String,
    @field:Size(max = 40) @field:Schema(description = "书签ID") val pageId: String?,  // 书签ID可能为null,在用户批量添加的时候,只会添加用户自定义书签,而不会关联到源书签
    @field:Size(max = 40) @field:Schema(description = "用户桌面排布ID") val layoutNodeId: String,

    /**
     * 用户自己写的标题。**`null` 表示用户没改过**，不是"改成了空"。
     *
     * 创建时刻意**不**从 `bookmark.title` 拷一份快照过来。拷过来之后"用户手改的值"和"创建时
     * 的快照"在数据上完全不可区分，于是永远判断不出新一次抓取该不该覆盖它 —— 这也是为什么
     * 页面改版后用户的标题要么永远是旧的、要么被静默冲掉，取决于当时走了哪条代码路径。
     * 留 NULL 之后覆盖策略是显然的：NULL 用页面标题，非 NULL 是用户的、永不覆盖。
     * 见根目录 `SITE_LAYERING_DESIGN.md` §6。
     */
    @field:Size(max = 200) @field:Schema(description = "书签标题(用户写的)；null 表示没改过") val title: String? = null,
    @field:Size(max = 1000) @field:Schema(description = "书签备注(用户写的)") val description: String? = null,
    @field:Size(max = 1000) @field:Schema(description = "书签完整URL(带参数)") val urlFull: String,    // http://sfz.uzuzuz.com.cn/?region=150303%26birthday=19520807%26sex=2%26num=19%26r=82,
    @field:Schema(description = "书签链接类型(域名/本地/IP/其他)") var linkType: BookmarkLinkType = BookmarkLinkType.OTHER,

    @field:Schema(description = "是否置顶") var pinned: Boolean = false,

    /**
     * 置顶区里的排列顺序，**与桌面树的排序是两回事**。
     *
     * 桌面顺序存在 `user_preference.node_sort_map_json`（key 是布局节点 id），它表达的是「某个节点
     * 在它所属的那一层里排第几」。而置顶区把**分散在各个文件夹里**的书签抽出来平铺成一行，它们
     * 之间的先后在那张表里根本无从表达 —— 两条来自不同文件夹的书签各自的 sort 只在自己那一层
     * 里有意义，拿来跨层比较得到的顺序是巧合。硬要用它排还有一个更糟的后果：改置顶区的顺序
     * 就会连带改动这条书签在它自己文件夹里的位置。
     *
     * 因此这一列归在「用户的这一次收藏」上，与 [pinned] 同层：置顶与置顶顺序本就是同一件事的
     * 两个方面。新置顶的书签排到末尾（见 `BookmarkUserLinkServiceImpl.setPinned`），取消置顶时
     * 不清零 —— 反正重新置顶会重新分配。
     */
    @field:Schema(description = "置顶区排序(越小越靠前)") var pinnedSort: Int = 0,
    @JsonIgnore @field:Schema(description = "用户打开该书签的累计次数(仅做记录)") var openCount: Int = 0,

    /**
     * `drainStuckLoading` 已经为这条占位补投递过多少次解析。
     *
     * 存在的理由是队头阻塞：`findStuckLoading` 按 `created_at ASC LIMIT n` 取行，一批「永远收不了口」
     * 的记录会稳定占住那 n 个名额，排在后面的行一轮都轮不到。没有计数就没有放弃的依据，
     * 补投递会以补投递锁的 TTL 为周期永远重试同一批。
     *
     * 「我方抓取服务不可用」(E307) **不计入**：那是我方故障，不该消耗用户这条书签的重试预算，
     * 解析链路在那条早退分支上会把它清零（见 BookmarkServiceImpl.parseAndNotice）。
     */
    @JsonIgnore @field:Schema(description = "补投递解析的累计次数(E307 不计)") var dispatchAttempts: Int = 0,

    @JsonIgnore @field:Schema(description = "创建时间") val createTime: LocalDateTime = LocalDateTime.now(),
    @JsonIgnore @field:Schema(description = "是否删除") val deleted: Boolean = false,
) {
    /**
     * 用户添加一个网址时的关联记录。
     *
     * [urlFull] 存的是**用户给的原始网址**（含全部参数），不是规范化后的地址 —— 用户点击永远
     * 走这一列。规范化只服务于去重与抓取目标，有的链接去掉未知参数就打不开。
     *
     * [title] / [description] 留空，见字段注释：抓取来的标题属于页面层，不该在这里存一份快照。
     */
    constructor(rawUrl: String, uid: String, nodeId: String, bookmark: PageEntity) : this(
        uid = uid,
        pageId = bookmark.id,
        urlFull = rawUrl,
        layoutNodeId = nodeId,
        linkType = WebsiteParser.classifyLinkType(bookmark.urlHost),
    )

    constructor(bookmark: PageEntity, nodeId: String, uid: String) : this(
        uid = uid,
        pageId = bookmark.id,
        urlFull = bookmark.rawUrl,
        layoutNodeId = nodeId,
        linkType = WebsiteParser.classifyLinkType(bookmark.urlHost),
    )

    /**
     *  在批量添加自定义书签的时候,用户的自定义书签是确定的,可以批量添加,但是不确定源书签不会在数据库中存在,所以先存储用户的自定义书签,关联书签ID设置为LOADING,
     *  后续对每个源书签单独检查,每检查完一个源书签,就根据源书签host,去找到用户书签的host,将书签ID补上.
     */
    constructor(uid: String, nodeId: String, raw: ChromeBookmarkRawData) : this(
        uid = uid,
        pageId = "LOADING",
        // 浏览器导出的标题就是页面 <title>，长过 200 字符的并不罕见，而 title 列是 varchar(200)。
        // 截断而不是拒绝：标题只是展示数据，丢几个字远好过整批导入被一条长标题带着回滚。
        title = raw.title.take(MAX_TITLE_LENGTH),
        // 同 addOne：存补全协议后的地址，不是原样字符串。导出文件里出现无协议网址虽少见，
        // 但一旦出现，这一列就不是个可跳转的地址了。解析不出来的（javascript: 小书签等）保留原样，
        // 它们本来就要走 parseAndResetUserItem 的「无源书签」收口路径。
        urlFull = normalizeImportedUrl(raw.url),
        layoutNodeId = nodeId,
        linkType = classifyRawLinkType(raw.url),
    )

    companion object {
        /** 对应 `bookmark_user_link.title varchar(200)`（见 deploy/schema.sql）。 */
        const val MAX_TITLE_LENGTH = 200

        private fun classifyRawLinkType(rawUrl: String): BookmarkLinkType =
            runCatching { WebsiteParser.classifyLinkType(WebsiteParser.urlWrapper(rawUrl).urlHost) }
                .getOrDefault(BookmarkLinkType.OTHER)

        private fun normalizeImportedUrl(rawUrl: String): String =
            runCatching { WebsiteParser.urlWrapper(rawUrl).urlRaw }.getOrDefault(rawUrl)
    }
}
