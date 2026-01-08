package top.tcyeee.bookmarkify.entity

import cn.hutool.core.bean.BeanUtil
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.entity.*
import top.tcyeee.bookmarkify.entity.enums.FunctionType
import top.tcyeee.bookmarkify.entity.enums.HomeItemType
import top.tcyeee.bookmarkify.entity.json.BookmarkDir
import top.tcyeee.bookmarkify.utils.OssUtils

data class BookmarkShow(
    @field:Schema(description = "关联书签ID") var bookmarkId: String? = null,
    @field:Schema(description = "关联用户自定义信息ID") var bookmarkUserLinkId: String? = null,
    @field:Schema(description = "书签标题") var title: String? = null,
    @field:Schema(description = "书签备注") var description: String? = null,
    @field:Schema(description = "完整url") var urlFull: String? = null,
    @field:Schema(description = "基础url") var urlBase: String? = null,
    @field:Schema(description = "小图标") var iconBase64: String? = null,
    @field:Schema(description = "网站活性") var isActivity: Boolean? = null,
    @field:Schema(description = "书签创建时间(Unix秒)") var createTime: Long? = null,
    @field:Schema(description = "目录层级(从根到目标)") var paths: List<String>? = null,
    @JsonIgnore @field:Schema(description = "用户ID") var uid: String? = null,
    @JsonIgnore @field:Schema(description = "大图尺寸") var hdSize: Int = 0,
    @JsonIgnore @field:Schema(description = "Host(用于拿不到name的情况下最后显示Title)") var urlHost: String? = null,
    @JsonIgnore @field:Schema(description = "在有manifest的情况下,替换title") var appName: String? = null,
    @JsonIgnore @field:Schema(description = "用户桌面排布ID") var layoutNodeId: String? = null,
    @field:Schema(description = "大图标OSS地址,带权限") var iconHdUrl: String? = null,
) {
    val isHd: Boolean get() = hdSize > 50

    /** 设置大图LOGO还有备用Title */
    fun initLogo(): BookmarkShow {
        // 为图片添加签名
        if (isHd) OssUtils.getLogoUrl(bookmarkId!!, hdSize, 256).also { iconHdUrl = it }

        // 设置备用title
        title = appName?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() } ?: urlHost
        return this
    }
}

data class HomeItemShow(
    @field:Schema(description = "ID") var id: String,
    @field:Schema(description = "UID") var uid: String,
    @field:Schema(description = "序号") var sort: Int = Int.MIN_VALUE,
    @field:Schema(description = "书签类型") var type: HomeItemType = HomeItemType.BOOKMARK,
    @field:Schema(description = "书签信息") var typeApp: BookmarkShow? = null,
    @field:Schema(description = "书签组信息") var typeDir: BookmarkDir? = null,
    @field:Schema(description = "系统功能入口") var typeFuc: FunctionType? = null,
    @JsonIgnore var bookmarkId: String? = null, // 用于新建书签时定位
)

data class UserInfoShow(
    @field:Schema(description = "UID") var uid: String,
    @field:Schema(description = "用户名称") var nickName: String,
    @field:Schema(description = "用户头像文件") var avatarUrl: String? = null,
    @field:Schema(description = "角色列表") var roles: List<String>? = null,
    @field:Schema(description = "首页路径") var homePath: String? = null,
) {
    constructor(entity: UserEntity, avatarUrl: String?) : this(
        uid = entity.id, nickName = entity.nickName, avatarUrl = avatarUrl
    )
}

class BacSettingVO(
    @field:Schema(description = "背景类型") var type: BackgroundType,
    @field:Schema(description = "背景ID") var backgroundLinkId: String,

    /* 如果是图片背景 */
    @field:Schema(description = "图片背景URL") var bacImgFile: UserFileVO? = null,
    /* 如果是渐变色背景 */
    @field:Schema(description = "背景渐变色") var bacColorGradient: Array<String>? = null,
    @field:Schema(description = "背景渐变方向") var bacColorDirection: Int? = null,
)

@Suppress("unused")
class BacGradientVO(
    @field:Schema(description = "背景ID") var id: String? = null,
    @field:Schema(description = "背景渐变色") var colors: Array<String>,
    @field:Schema(description = "背景渐变方向") var direction: Int,
)

/** 默认背景资源合集 */
data class DefaultBackgroundsResponse(
    @field:Schema(description = "默认渐变背景列表") val gradients: List<BacGradientVO> = emptyList(),
    @field:Schema(description = "默认图片背景列表") val images: List<UserFileVO> = emptyList(),
)

data class UserFileVO(
    @field:Schema(description = "文件ID") var id: String,
    @field:Schema(description = "文件完整URL") var fullName: String,
)

data class UserPreferenceVO(
    @field:Schema(description = "书签打开方式") var bookmarkOpenMode: BookmarkOpenMode = BookmarkOpenMode.NEW_TAB,
    @field:Schema(description = "是否开启极简模式") var minimalMode: Boolean = false,
    @field:Schema(description = "书签排列方式") var bookmarkLayout: BookmarkLayoutMode = BookmarkLayoutMode.DEFAULT,
    @field:Schema(description = "是否显示标题") var showTitle: Boolean = true,
    @field:Schema(description = "翻页方式") var pageMode: PageTurnMode = PageTurnMode.VERTICAL_SCROLL,
    @field:Schema(description = "背景配置") var imgBacShow: BacSettingVO? = null,
) {
    constructor(entity: UserPreferenceEntity) : this() {
        BeanUtil.copyProperties(entity, this)
    }
}

data class UserLayoutNodeVO(
    @field:Schema(description = "节点ID") val id: String,
    @field:Schema(description = "父节点ID") val parentId: String? = null,
    @field:Schema(description = "排序") val sort: Int = Int.MAX_VALUE,
    @field:Schema(description = "节点类型") val type: NodeTypeEnum = NodeTypeEnum.BOOKMARK,
    @field:Schema(description = "节点(文件夹)名称") val name: String? = null,

    /* 三选一 */
    @field:Schema(description = "书签信息") var typeApp: BookmarkShow? = null,
    @field:Schema(description = "系统功能入口") var typeFuc: FunctionType? = null,
    @field:Schema(description = "子节点") val children: MutableList<UserLayoutNodeVO> = mutableListOf()
) {

    // 通过书签构造单桌面节点
    constructor(nodeEntity: UserLayoutNodeEntity, bookmarkShow: BookmarkShow) : this(
        id = nodeEntity.id,
        type = NodeTypeEnum.BOOKMARK,
        typeApp = bookmarkShow
    ) {
        BeanUtil.copyProperties(nodeEntity, this)
    }
}

data class LogoVO(
    @field:Schema(description = "base64") var iconBase64: String? = null,
    @field:Schema(description = "最大LOGO尺寸") var maximalLogoSize: Int = 0,
    @field:Schema(description = "最近更新时间") var logoCheckFlag: Boolean = false, // 是否主动配置了LOGO

    // TODO LOGO图片类型 ICO-Base64 / PNG
    // TODO 内边距
)
