package top.tcyeee.bookmarkify.server

import top.tcyeee.bookmarkify.entity.enums.KafkaTopicType

interface IKafkaMessageService {
    fun send(topic: KafkaTopicType, message: String)
}

