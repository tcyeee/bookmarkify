package top.tcyeee.bookmarkify.config.kafka

import cn.hutool.json.JSONObject
import cn.hutool.json.JSONUtil
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.UserLayoutNodeVO
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.entity.entity.BookmarkUserLink
import top.tcyeee.bookmarkify.entity.entity.UserLayoutNodeEntity
import top.tcyeee.bookmarkify.entity.enums.ParseStatusEnum
import top.tcyeee.bookmarkify.mapper.BookmarkMapper
import top.tcyeee.bookmarkify.mapper.BookmarkUserLinkMapper
import top.tcyeee.bookmarkify.mapper.UserLayoutNodeMapper
import top.tcyeee.bookmarkify.mapper.WebsiteLogoMapper
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.utils.OssUtils
import top.tcyeee.bookmarkify.utils.SocketUtils
import top.tcyeee.bookmarkify.utils.WebsiteParser
import java.time.LocalDateTime

@Component
@ConditionalOnProperty(prefix = "bookmarkify.kafka", name = ["enabled"], havingValue = "true")
class KafkaMessageListener(
    private val bookmarkUserLinkMapper: BookmarkUserLinkMapper,
    private val bookmarkMapper: BookmarkMapper,
    private val websiteLogoMapper: WebsiteLogoMapper,
    private val layoutNodeMapper: UserLayoutNodeMapper,
    private val bookmarkService: IBookmarkService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${bookmarkify.kafka.default-topic:bookmarkify-events}", "BOOKMARK_PRASE"],
        groupId = "\${spring.kafka.consumer.group-id:bookmarkify-group}"
    )
    fun onMessage(record: ConsumerRecord<String, String>) {
        log.info("[Kafka] received topic=${record.topic()} message=${record.value()}")
        val obj = runCatching { JSONUtil.parseObj(record.value()) }.getOrElse {
            log.warn("[Kafka] 无法解析消息为JSON: ${it.message}")
            it.printStackTrace()
            throw CommonException(ErrorType.E232)
        }
        when (obj.getStr("action")) {
            KafkaMethodsEnums.PARSE_NOTICE_EXISTING.name -> handleExisting(obj.getStr("uid"), obj)
            KafkaMethodsEnums.PARSE_NOTICE_NEW.name -> handleNew(obj.getStr("uid"), obj)
            else -> throw CommonException(ErrorType.E231)
        }
    }

    private fun handleExisting(uid: String, obj: JSONObject) {
        val bookmark = obj.getJSONObject("bookmark").toBean(BookmarkEntity::class.java)
        val userLinkId = obj.getStr("userLinkId")
        val nodeLayoutId = obj.getStr("nodeLayoutId")
        parseBookmarkAndSave(bookmark)
        findBookmarkAndNotice(userLinkId, nodeLayoutId, uid)
    }

    private fun handleNew(uid: String, obj: JSONObject) {
        val bookmark = obj.getJSONObject("bookmark").toBean(BookmarkEntity::class.java)
        val parentNodeId = obj.getStr("parentNodeId")

        parseBookmarkAndSave(bookmark)
        val layoutEntity = UserLayoutNodeEntity(uid = uid, parentId = parentNodeId).also { layoutNodeMapper.insert(it) }
        val userLinkEntity = BookmarkUserLink(bookmark, layoutEntity.id, uid).also { bookmarkUserLinkMapper.insert(it) }
        findBookmarkAndNotice(userLinkEntity.id, layoutEntity.id, uid)
    }

    /**
     * 1.解析书签
     * 2.保存到数据库
     *
     * 这里的书签大概率是临时生成的,数据库可能会有一模一样且不需要进行任何处理的完整书签
     * 所以先进判断，如果数据库有，并且verifyFlag为True,那么就不再进行解析了
     * verifyFlag: 手动认证开关, 这个字段如果为真, 该不会再进行任何变动.
     */
    private fun parseBookmarkAndSave(bookmark: BookmarkEntity): BookmarkEntity {
        // 如果书签依旧解析了,则不需要继续解析
        val oldBookmarkEneity = bookmarkMapper.selectById(bookmark.id)
        if (oldBookmarkEneity != null && oldBookmarkEneity.verifyFlag) return oldBookmarkEneity

        log.warn("[CHECK] 开始解析域名:${bookmark.rawUrl}")
        val wrapper = runCatching { WebsiteParser.parse(bookmark.rawUrl) }.getOrElse {
            if (it.message.toString().contains("403")) bookmark.parseStatus = ParseStatusEnum.BLOCKED
            bookmark.parseErrMsg = it.message.toString()
            bookmark.isActivity = false
            bookmarkMapper.insertOrUpdate(bookmark)
            it.printStackTrace()
            throw CommonException(ErrorType.E230)
        }
            // 填充bookmark基础信息 以及 bookmark-ico-base64信息
            .also { bookmark.initBaseInfo(it) }
            // 更新书签
            .also { bookmarkMapper.insertOrUpdate(bookmark) }

        // 保存网站LOGO/OG图片到OSS和数据库
        if (wrapper.distinctIcons.isNullOrEmpty()) return bookmark
        OssUtils.restoreWebsiteLogoAndOg(wrapper.distinctIcons, bookmark.id)
            // 更新/保存LOGO到数据库
            ?.also { websiteLogoMapper.insertOrUpdate(it) }
            // 更新/保存最大图标信息
            ?.also { bookmark.setMaximalLogoSize(it.width) }
        return bookmark
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