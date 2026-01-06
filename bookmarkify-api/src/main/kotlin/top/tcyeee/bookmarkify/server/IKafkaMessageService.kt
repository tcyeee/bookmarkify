package top.tcyeee.bookmarkify.server

interface IKafkaMessageService {
    fun send(topic: String, message: String)
}

