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
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.server.IBookmarkService

@Component
@ConditionalOnProperty(prefix = "bookmarkify.kafka", name = ["enabled"], havingValue = "true")
class KafkaMessageListener(private val bookmarkService: IBookmarkService) {
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
            KafkaMethodsEnums.PARSE_NOTICE_EXISTING.name -> handleExisting(obj)
            KafkaMethodsEnums.PARSE_NOTICE_NEW.name -> handleNew(obj)
            else -> throw CommonException(ErrorType.E231)
        }
    }

    private fun handleExisting(obj: JSONObject) {
        val uid = obj.getStr("uid")
        val bookmark = obj.getJSONObject("bookmark").toBean(BookmarkEntity::class.java)
        val userLinkId = obj.getStr("userLinkId")
        val nodeLayoutId = obj.getStr("nodeLayoutId")
        bookmarkService.bookmarkParseAndNotice(uid, bookmark, userLinkId, nodeLayoutId)
    }

    private fun handleNew(obj: JSONObject) {
        val uid = obj.getStr("uid")
        val bookmark = obj.getJSONObject("bookmark").toBean(BookmarkEntity::class.java)
        val parentNodeId = obj.getStr("parentNodeId")
        bookmarkService.bookmarkParseAndNotice(uid, bookmark, parentNodeId)
    }
}