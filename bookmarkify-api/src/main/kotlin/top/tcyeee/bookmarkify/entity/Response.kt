package top.tcyeee.bookmarkify.entity

import cn.hutool.core.bean.BeanUtil
import cn.hutool.core.util.EnumUtil
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.dto.UserSetting
import top.tcyeee.bookmarkify.entity.entity.BackgroundType
import top.tcyeee.bookmarkify.entity.entity.HomeItem
import top.tcyeee.bookmarkify.entity.entity.UserEntity
import top.tcyeee.bookmarkify.entity.entity.UserFile
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

    @JsonIgnore @field:Schema(description = "用户ID") var uid: String? = null,
    @JsonIgnore @field:Schema(description = "大图尺寸") var hdSize: Int = 0,
    @JsonIgnore @field:Schema(description = "Host(用于拿不到name的情况下最后显示Title)") var urlHost: String? = null,
    @JsonIgnore @field:Schema(description = "在有manifest的情况下,替换title") var appName: String? = null,

    @field:Schema(description = "大图标OSS地址,带权限") var iconHdUrl: String? = null,
) {
    val isHd: Boolean get() = hdSize > 50

    /**
     * 设置大图LOGO还有备用Title
     */
    fun initLogo(): BookmarkShow {
        // 为图片添加签名
        if (isHd) OssUtils.getLogoUrl(bookmarkId!!, hdSize, 256)
            .also { iconHdUrl = it }

        // 设置备用title
        title = appName
            ?.takeIf { it.isNotBlank() } ?: title
            ?.takeIf { it.isNotBlank() } ?: urlHost
        return this
    }
}

data class HomeItemShow(
    @field:Schema(description = "ID") var id: String,
    @field:Schema(description = "UID") var uid: String,
    @field:Schema(description = "序号") var sort: Int = 99,
    @field:Schema(description = "书签类型") var type: HomeItemType = HomeItemType.BOOKMARK,

    @field:Schema(description = "书签信息") var typeApp: BookmarkShow? = null,
    @field:Schema(description = "书签组信息") var typeDir: BookmarkDir? = null,
    @field:Schema(description = "系统功能入口") var typeFuc: FunctionType? = null,

    @JsonIgnore var bookmarkId: String? = null,  // 用于新建书签时定位
) {

    constructor(item: HomeItem, database: Map<String, BookmarkShow>) : this(id = item.id, uid = item.uid) {
        BeanUtil.copyProperties(item, this)
        when (item.type) {
            HomeItemType.BOOKMARK_DIR -> this.typeDir = BookmarkDir(database, item.bookmarkDirJson)
            HomeItemType.BOOKMARK -> this.typeApp = database[item.bookmarkUserLinkId]
            HomeItemType.FUNCTION -> this.typeFuc = EnumUtil.getEnumAt(FunctionType::class.java, item.functionId ?: 0)
        }
    }

    constructor(id: String, uid: String, bookmarkId: String) : this(
        id, uid, bookmarkId = bookmarkId, type = HomeItemType.BOOKMARK
    )

    constructor(uid: String, itemId: String, bookmarkShow: BookmarkShow) : this(
        id = itemId,
        uid = uid,
        typeApp = bookmarkShow
    )
}

data class UserInfoShow(
    @field:Schema(description = "UID") var uid: String,
    @field:Schema(description = "用户名称") var nickName: String,
    @field:Schema(description = "用户头像文件") var avatar: UserFile? = null,
    @field:Schema(description = "用户设置信息") var userSetting: UserSetting? = null,

    @JsonIgnore @field:Schema(description = "用户头像文件ID") var avatarFileId: String? = null,
) {
    constructor(user: UserEntity) : this(uid = user.id, nickName = user.nickName) {
        BeanUtil.copyProperties(user, this)
    }
}

class BacSettingVO(
    @JsonIgnore @field:Schema(description = "用户ID") var uid: String,
    @JsonIgnore @field:Schema(description = "背景ID") var backgroundLinkId: String,
    @field:Schema(description = "背景类型") var type: BackgroundType,

    /* 如果是图片背景 */
    @field:Schema(description = "图片背景") var bacImgFile: UserFile? = null,

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

/**
 * 默认背景资源合集
 */
class DefaultBackgroundsResponse(
    @field:Schema(description = "默认渐变背景列表") val gradients: List<BacGradientVO> = emptyList(),
    @field:Schema(description = "默认图片背景列表") val images: List<UserFileVO> = emptyList(),
)

data class UserFileVO(
    @field:Schema(description = "文件ID") var id: String,
    @field:Schema(description = "文件完整URL") var fullName: String,
)