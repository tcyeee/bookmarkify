package top.tcyeee.bookmarkify.server.impl

import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.UserLayoutNodeVO
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.entity.enums.KafkaTopicType
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.mapper.BookmarkMapper
import top.tcyeee.bookmarkify.mapper.BookmarkUserLinkMapper
import top.tcyeee.bookmarkify.mapper.UserLayoutNodeMapper
import top.tcyeee.bookmarkify.mapper.WebsiteLogoMapper
import top.tcyeee.bookmarkify.server.IKafkaMessageService
import top.tcyeee.bookmarkify.utils.OssUtils
import top.tcyeee.bookmarkify.utils.SocketUtils
import top.tcyeee.bookmarkify.utils.WebsiteParser
import java.time.LocalDateTime

@Service
class KafkaMessageServiceImpl(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val bookmarkUserLinkMapper: BookmarkUserLinkMapper,
    private val bookmarkMapper: BookmarkMapper,
    private val websiteLogoMapper: WebsiteLogoMapper,
    private val layoutNodeMapper: UserLayoutNodeMapper
) : IKafkaMessageService {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun bookmarkParse(bookmark: BookmarkEntity) {
        val type = KafkaTopicType.BOOKMARK_PRASE
        kafkaTemplate.send(type.name, bookmark.json).whenComplete { res, err ->
            if (err != null) throw CommonException(ErrorType.E229, "type:${type.name},error:${err.message}")
            val metadata = res.recordMetadata
            log.info("[Kafka] send topic=${metadata.topic()} partition=${metadata.partition()} offset=${metadata.offset()}")
        }
    }

    override fun bookmarkParseAndNotice(
        uid: String, bookmark: BookmarkEntity, userLinkId: String, nodeLayoutId: String
    ) {
        this.parseBookmark(bookmark)
        this.findBookmarkAndNotice(userLinkId, nodeLayoutId, uid)
    }

    private fun parseBookmark(bookmark: BookmarkEntity) {
        log.warn("[CHECK] 开始解析域名:${bookmark.rawUrl}")
        val wrapper = runCatching { WebsiteParser.parse(bookmark.rawUrl) }.getOrElse {
                if (it.message.toString().contains("403")) bookmark.parseStatus = ParseStatusEnum.BLOCKED
                bookmark.parseErrMsg = it.message.toString()
                bookmark.isActivity = false
                bookmarkMapper.insertOrUpdate(bookmark)
                it.printStackTrace()
                return
            }
            // 填充bookmark基础信息 以及 bookmark-ico-base64信息
            .also { bookmark.initBaseInfo(it) }
            // 更新书签
            .also { bookmarkMapper.insertOrUpdate(bookmark) }

        // 保存网站LOGO/OG图片到OSS和数据库
        if (wrapper.distinctIcons.isNullOrEmpty()) return
        OssUtils.restoreWebsiteLogoAndOg(wrapper.distinctIcons, bookmark.id)
            // 更新/保存LOGO到数据库
            ?.also { websiteLogoMapper.insertOrUpdate(it) }
            // 更新/保存最大图标信息
            ?.also { bookmark.setMaximalLogoSize(it.width) }

    }

    private fun findBookmarkAndNotice(bookmarkUserLinkId: String, layoutNodeId: String, uid: String) {
        // 找到用户自定义书签
        val bookmarkShow = bookmarkUserLinkMapper.findShowById(bookmarkUserLinkId).initLogo()
        // 找到用户的单条布局信息
        val layoutEntity = layoutNodeMapper.selectById(layoutNodeId)
        // 通知到前端
        UserLayoutNodeVO(layoutEntity, bookmarkShow).also { SocketUtils.homeItemUpdate(uid, it) }
    }

    // 在BookmarkEntity设置LOGO最大尺寸
    private fun BookmarkEntity.setMaximalLogoSize(maximal: Int) {
        this.maximalLogoSize = maximal
        this.isActivity = true
        this.updateTime = LocalDateTime.now()
        bookmarkMapper.insertOrUpdate(this)
    }
}


