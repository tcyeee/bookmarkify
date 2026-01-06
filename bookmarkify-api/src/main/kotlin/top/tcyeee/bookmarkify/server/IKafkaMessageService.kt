package top.tcyeee.bookmarkify.server

import top.tcyeee.bookmarkify.entity.entity.Bookmark

interface IKafkaMessageService {
    /**
     * 解析书签,然后保存到数据库
     * @param bookmark bookmark
     */
    fun bookmarkParse(bookmark: Bookmark)

    /**
     * 解析书签,然后保存到数据库,同时通知到用户
     * @param uid user-id
     * @param bookmark bookmark-id
     * @param userLinkId bookmark-user-link-id
     * @param homeItemId home-item-id
     */
    fun bookmarkParseAndNotice(uid: String, bookmark: Bookmark, userLinkId: String, homeItemId: String)
}

