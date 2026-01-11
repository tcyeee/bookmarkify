package top.tcyeee.bookmarkify.config.kafka

/**
 * @author tcyeee
 * @date 1/6/26 17:10
 */
enum class KafkaTopicEnums {
    /* 默认Topic */
    BOOKMARKIFY,
}

enum class KafkaMethodsEnums {
    LINKT_TEST,
    PARSE_NOTICE_EXISTING,
    PARSE_NOTICE_NEW,
    BOOKMARK_PARSE_AND_RESET_USER_ITEM
}
