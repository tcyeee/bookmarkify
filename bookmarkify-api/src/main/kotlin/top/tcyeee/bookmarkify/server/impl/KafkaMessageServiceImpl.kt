package top.tcyeee.bookmarkify.server.impl

import cn.hutool.json.JSONUtil
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.config.kafka.KafkaMethodsEnums
import top.tcyeee.bookmarkify.config.kafka.KafkaTopicEnums
import top.tcyeee.bookmarkify.server.IKafkaMessageService

@Service
class KafkaMessageServiceImpl(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) : IKafkaMessageService {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun linkTest(message: String) = JSONUtil.createObj()
        .set("action", KafkaMethodsEnums.LINKT_TEST.name)
        .set("message", message).toString()
        .let { this.send(it) }

    override fun bookmarkParseAndResetUserItem(uid: String, rawUrl: String, userLinkId: String, layoutNodeId: String) = JSONUtil.createObj()
        .set("action", KafkaMethodsEnums.BOOKMARK_PARSE_AND_RESET_USER_ITEM.name)
        .set("uid", uid)
        .set("rawUrl", rawUrl)
        .set("userLinkId", userLinkId)
        .set("layoutNodeId", layoutNodeId)
        .toString()
        .let { this.send(it) }

    override fun bookmarkParse(bookmarkId:String) = JSONUtil.createObj()
        .set("action", KafkaMethodsEnums.BOOKMARK_PARSE.name)
        .set("bookmarkId", bookmarkId).toString()
        .let { this.send(it) }

    override fun bookmarkParseAndNotice(
        uid: String, bookmarkId: String, userLinkId: String, nodeLayoutId: String
    ) = JSONUtil.createObj().set("action", KafkaMethodsEnums.PARSE_NOTICE_EXISTING.name).set("uid", uid)
        .set("bookmarkId", bookmarkId).set("userLinkId", userLinkId).set("nodeLayoutId", nodeLayoutId).toString()
        .let { this.send(it) }

    override fun bookmarkParseAndNotice(uid: String, bookmarkId: String, parentNodeId: String?) =
        JSONUtil.createObj().set("action", KafkaMethodsEnums.PARSE_NOTICE_NEW.name)
            .set("uid", uid)
            .set("bookmarkId", bookmarkId)
            .set("parentNodeId", parentNodeId).toString()
            .let { this.send(it) }

    private fun send(message: String?) {
        kafkaTemplate.send(KafkaTopicEnums.BOOKMARKIFY.name, message).whenComplete { res, err ->
            if (err != null) throw CommonException(ErrorType.E229, err.message)
            val metadata = res.recordMetadata
            log.info("[Kafka] send topic=${metadata.topic()} partition=${metadata.partition()} offset=${metadata.offset()}")
        }
    }
}


