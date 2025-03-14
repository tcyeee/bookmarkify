package top.tcyeee.bookmarkify.entity.response

import cn.hutool.core.util.StrUtil
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema

/**
 * 用户书签展示信息
 *
 * @author tcyeee
 * @date 3/11/24 22:44
 */
data class BookmarkShow(
    var uid: String? = null,
    var bookmarkId: String? = null,
    var bookmarkUserLinkId: String? = null,

    @Schema(description = "书签标题") var userTitle: String? = null,
    @Schema(description = "书签备注") var description: String? = null,
    @Schema(description = "完整链接路径") var urlFull: String? = null,

    @Schema(description = "是否失效") var isActivity: Boolean = false,
    @Schema(description = "图标是否存在") var iconActivity: Boolean = false,
    @Schema(description = "是否可以启用大图标") var iconHd: Boolean = false,
    @Schema(description = "需要特别指定的图标地址") var iconUrl: String? = null,

    /* 计算属性 */
    @JsonIgnore var title: String? = null,
    @JsonIgnore var userDescription: String? = null,
    @JsonIgnore var baseTitle: String? = null,
    @JsonIgnore var baseDescription: String? = null,
) {

    /**
     * 初始化书签展示数据
     *
     * @param imgPrefix 图标前缀
     * @return 书签数据
     */
    fun setPrefix(imgPrefix: String): BookmarkShow {
        this.iconUrl = if (StrUtil.isBlank(this.iconUrl)) null else imgPrefix + this.iconUrl
        this.title = if (StrUtil.isBlank(this.userTitle)) this.baseTitle else this.userTitle
        this.description = if (StrUtil.isBlank(this.userDescription)) this.baseDescription else this.userDescription
        return this
    }
}