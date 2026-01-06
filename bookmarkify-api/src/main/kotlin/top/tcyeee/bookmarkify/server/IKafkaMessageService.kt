package top.tcyeee.bookmarkify.server

interface IKafkaMessageService {
    fun send(message: String)

    fun send(topic: String, message: String)
}

