package top.tcyeee.bookmarkify.server.impl

import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import top.tcyeee.bookmarkify.config.exception.CommonException
import top.tcyeee.bookmarkify.config.exception.ErrorType
import top.tcyeee.bookmarkify.entity.entity.Bookmark
import top.tcyeee.bookmarkify.entity.enums.KafkaTopicType
import top.tcyeee.bookmarkify.server.IBookmarkService
import top.tcyeee.bookmarkify.server.IKafkaMessageService

@Service
class KafkaMessageServiceImpl(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val bookmarkService: IBookmarkService
) : IKafkaMessageService {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun bookmarkParse(bookmark: Bookmark) {
        val type = KafkaTopicType.BOOKMARK_PRASE
        kafkaTemplate.send(type.name, bookmark.json).whenComplete { res, err ->
            if (err != null) throw CommonException(ErrorType.E229, "type:${type.name},error:${err.message}")
            val metadata = res.recordMetadata
            log.info("[Kafka] send topic=${metadata.topic()} partition=${metadata.partition()} offset=${metadata.offset()}")
        }
    }

    override fun bookmarkParseAndNotice(uid: String, bookmark: Bookmark, userLinkId: String, homeItemId: String) {
        bookmarkService.parseBookmark(bookmark)
        bookmarkService.findBookmarkAndNotice(userLinkId, homeItemId, uid)
    }
}

