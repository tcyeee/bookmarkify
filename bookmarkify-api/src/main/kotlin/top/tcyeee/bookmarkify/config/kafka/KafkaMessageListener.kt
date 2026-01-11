package top.tcyeee.bookmarkify.config.kafka

import cn.hutool.crypto.digest.DigestUtil
import cn.hutool.json.JSONObject
import cn.hutool.json.JSONUtil
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
import top.tcyeee.bookmarkify.server.IBookmarkService

@Component
class KafkaMessageListener(private val bookmarkService: IBookmarkService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["BOOKMARKIFY"], groupId = "BOOKMARKIFY")
    fun onMessage(record: ConsumerRecord<String, String>) {
        log.info("[Kafka] received key=${DigestUtil.md5Hex(record.toString())} message=${record.value()}")
        val obj = runCatching { JSONUtil.parseObj(record.value()) }.getOrElse {
            log.warn("[Kafka] 无法解析消息为JSON: ${it.message}")
            it.printStackTrace()
            return
        }
        when (obj.getStr("action")) {
            KafkaMethodsEnums.LINKT_TEST.name -> linkTest(obj)
            KafkaMethodsEnums.BOOKMARK_PARSE.name -> bookmarkParse(obj)
            KafkaMethodsEnums.PARSE_NOTICE_EXISTING.name -> parseNoticeExisting(obj)
            KafkaMethodsEnums.PARSE_NOTICE_NEW.name -> handleNew(obj)
            KafkaMethodsEnums.BOOKMARK_PARSE_AND_RESET_USER_ITEM.name -> bookmarkParseAndResetUserItem(obj)
            else -> {
                log.warn("[Kafka] unknown action: ${obj.getStr("action")}")
                return
            }
        }
    }

    private fun linkTest(obj: JSONObject) {
        val message = obj.getStr("message")
        log.info("[Kafka]: $message")
    }

    private fun bookmarkParse(obj: JSONObject) {
        val bookmarkId = obj.getStr("bookmarkId")
        bookmarkService.kafkaBookmarkParse(bookmarkId)
    }

    private fun parseNoticeExisting(obj: JSONObject) {
        val uid = obj.getStr("uid")
        val bookmarkId = obj.getStr("bookmarkId")
        val userLinkId = obj.getStr("userLinkId")
        val nodeLayoutId = obj.getStr("nodeLayoutId")
        bookmarkService.kafKaBookmarkParseAndNotice(uid, bookmarkId, userLinkId, nodeLayoutId)
    }

    private fun handleNew(obj: JSONObject) {
        val uid = obj.getStr("uid")
        val parentNodeId = obj.getStr("parentNodeId")
        val bookmarkId = obj.getStr("bookmarkId")
        bookmarkService.kafKaBookmarkParseAndNotice(uid, bookmarkId, parentNodeId)
    }

    private fun bookmarkParseAndResetUserItem(obj: JSONObject) {
        val uid = obj.getStr("uid")
        val rawUrl = obj.getStr("rawUrl")
        bookmarkService.kafkaBookmarkParseAndResetUserItem(uid, rawUrl)
    }
}