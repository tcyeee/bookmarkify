package top.tcyeee.bookmarkify.entity.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonProperty
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import java.time.LocalDateTime

data class IframelyResponse(
    val url: String? = null,
    val rel: List<String>? = null,
    val html: String? = null,
    val error: String? = null,
    val meta: IframelyMeta? = null,
    val links: IframelyLinks? = null,
) {
    /** 将 iframely 解析结果填充到已有书签实体并返回 */
    fun entity(bookmark: BookmarkEntity): BookmarkEntity = bookmark.apply {
        appName = meta?.site
        title = meta?.title
        description = meta?.description
        isActivity = true
        parseStatus = ParseStatusEnum.SUCCESS
        updateTime = LocalDateTime.now()
    }
}

data class IframelyMeta(
    val title: String? = null,
    val description: String? = null,
    val author: String? = null,
    @field:JsonProperty("author_url") val authorUrl: String? = null,
    val site: String? = null,
    val canonical: String? = null,
    val duration: Int? = null,
    val date: String? = null,
    val medium: String? = null,
)

/**
 * iframely links 对象：每个字段均为"单元素时返回 object，多元素时返回 array"，
 * 使用 ACCEPT_SINGLE_VALUE_AS_ARRAY 统一转为 List 处理
 */
data class IframelyLinks(
    @field:JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val thumbnail: List<IframelyLink>? = null,
    @field:JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val icon: List<IframelyLink>? = null,
    @field:JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val logo: List<IframelyLink>? = null,
    @field:JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val player: List<IframelyLink>? = null,
    @field:JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val image: List<IframelyLink>? = null,
    @field:JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val reader: List<IframelyLink>? = null,
)

data class IframelyLink(
    val href: String? = null,
    val rel: List<String>? = null,
    val type: String? = null,
    val html: String? = null,
    val media: IframelyMedia? = null,
)

data class IframelyMedia(
    val width: Int? = null,
    val height: Int? = null,
    @field:JsonProperty("aspect-ratio") val aspectRatio: Double? = null,
)
