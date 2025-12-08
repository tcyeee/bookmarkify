package top.tcyeee.bookmarkify.entity

import cn.hutool.core.bean.BeanUtil
import cn.hutool.core.util.EnumUtil
import cn.hutool.core.util.IdUtil
import cn.hutool.core.util.StrUtil
import com.baomidou.mybatisplus.annotation.TableId
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema
import top.tcyeee.bookmarkify.entity.dto.BookmarkUrl
import top.tcyeee.bookmarkify.entity.dto.UserSessionInfo
import top.tcyeee.bookmarkify.entity.dto.UserSetting
import top.tcyeee.bookmarkify.entity.entity.BackgroundType
import top.tcyeee.bookmarkify.entity.entity.Bookmark
import top.tcyeee.bookmarkify.entity.entity.HomeItem
import top.tcyeee.bookmarkify.entity.entity.UserEntity
import top.tcyeee.bookmarkify.entity.entity.UserFile
import top.tcyeee.bookmarkify.entity.enums.FunctionType
import top.tcyeee.bookmarkify.entity.enums.HomeItemType
import top.tcyeee.bookmarkify.entity.json.BookmarkDir

data class BookmarkAddOneParams(
    @field:Schema(description = "添加的域名") var url: String,
)

data class BookmarkDetail(
    var bookmark: Bookmark,   // 公共书签
    var paths: List<String>,  // 路径  eg: /公共书签/娱乐/bilibili.com
    var url: BookmarkUrl,     // 用于数据清洗
    var bookmarkUserLinkId: String // 用户关联ID
) {
    constructor(paths: List<String>, url: BookmarkUrl, addDate: String, name: String) : this(
        bookmarkUserLinkId = "", bookmark = Bookmark(url, addDate, name), paths = paths, url = url
    )
}

data class BookmarkShow(
    var uid: String? = null,
    var bookmarkId: String? = null,
    var bookmarkUserLinkId: String? = null,

    @field:Schema(description = "书签标题") var title: String? = null,
    @field:Schema(description = "书签备注") var description: String? = null,
    @field:Schema(description = "完整链接路径") var urlFull: String? = null,

    @field:Schema(description = "是否失效") var isActivity: Boolean = false,
    @field:Schema(description = "图标是否存在") var iconActivity: Boolean = false,
    @field:Schema(description = "是否可以启用大图标") var iconHd: Boolean = false,
    @field:Schema(description = "图标地址") var iconUrlFull: String? = null,

    /* 计算属性 */
    @JsonIgnore var iconUrl: String? = null,
    @JsonIgnore var userTitle: String? = null,
    @JsonIgnore var userDescription: String? = null,
    @JsonIgnore var baseTitle: String? = null,
    @JsonIgnore var baseDescription: String? = null,
) {

    /**
     * 数据清洗初始化
     *
     * @param imgPrefix 图标前缀
     * @return 书签数据
     */
    fun clean(imgPrefix: String): BookmarkShow {
        this.iconUrlFull = if (this.iconActivity) imgPrefix + iconUrl else null
        this.title = if (StrUtil.isBlank(this.userTitle)) this.baseTitle else this.userTitle
        this.description = if (StrUtil.isBlank(this.userDescription)) this.baseDescription else this.userDescription
        return this
    }
}

data class HomeItemShow(
    var id: String,
    var uid: String,

    @field:Schema(description = "序号") var sort: Int = 99,
    @field:Schema(description = "书签类型") var type: HomeItemType = HomeItemType.BOOKMARK,

    var bookmarkId: String? = null,     // 用于新建书签时定位
    var typeApp: BookmarkShow? = null,  // 书签信息
    var typeDir: BookmarkDir? = null,   // 书签组信息
    var typeFuc: FunctionType? = null,  // 系统功能入口
) {

    constructor(item: HomeItem, database: Map<String, BookmarkShow>, imgPrefix: String) : this(
        id = item.id, uid = item.uid
    ) {
        BeanUtil.copyProperties(item, this)
        when (item.type) {
            HomeItemType.BOOKMARK_DIR -> this.typeDir = BookmarkDir(database, item.bookmarkDirJson)
            HomeItemType.BOOKMARK -> this.typeApp = database[item.bookmarkUserLinkId]?.clean(imgPrefix)
            HomeItemType.FUNCTION -> this.typeFuc = EnumUtil.getEnumAt(FunctionType::class.java, item.functionId ?: 0)
        }
    }

    constructor(id: String, uid: String, bookmarkId: String) : this(
        id, uid, bookmarkId = bookmarkId, type = HomeItemType.BOOKMARK
    )
}

data class UserAuthEntityVo(
    var uid: String,
    val token: String,
    val nickName: String? = null,
    val mail: String? = null,
) {
    constructor(user: UserSessionInfo, token: String) : this(
        uid = user.uid, token = token, nickName = user.nickName, mail = user.email
    )
}

data class UserInfoShow(
    var uid: String,
    var token: String,
    var nickName: String,
    var phone: String? = null,
    var email: String? = null,
    var avatarFileId: String? = null,
    var verified: Boolean? = null,       // 是否为验证过的账户(例如绑定手机号,绑定邮箱等)
    var avatar: UserFile? = null,

    // 用户设置信息
    var userSetting: UserSetting? = null,
) {
    constructor(user: UserEntity, token: String) : this(
        uid = user.id,
        token = token,
        nickName = user.nickName,
    ) {
        BeanUtil.copyProperties(user, this)
    }
}

data class BacSettingVO(
    @field:Schema(description = "用户ID") var uid: String,
    @field:Schema(description = "背景类型") var type: BackgroundType,

    @JsonIgnore @field:Schema(description = "背景ID") var backgroundLinkId: String,

    /* 如果是图片背景 */
    @field:Schema(description = "图片背景") var bacImgFile: UserFile? = null,

    /* 如果是渐变色背景 */
    @field:Schema(description = "背景渐变色(JSON)") var bacColorGradient: String? = null,
    @field:Schema(description = "背景渐变方向") var bacColorDirection: Int? = null,
)