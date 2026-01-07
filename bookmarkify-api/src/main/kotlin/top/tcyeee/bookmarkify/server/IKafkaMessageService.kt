package top.tcyeee.bookmarkify.server

import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity

interface IKafkaMessageService {
    /**
     * 解析书签,然后保存到数据库
     * @param bookmark bookmark
     */
    fun bookmarkParse(bookmark: BookmarkEntity)

    /**
     * 解析书签,然后保存到数据库,同时通知到用户
     * @param uid user-id
     * @param bookmark bookmark-id
     * @param userLinkId bookmark-user-link-id
     * @param nodeLayoutId 关联的桌面布局ID
     */
    fun bookmarkParseAndNotice(uid: String, bookmark: BookmarkEntity, userLinkId: String, nodeLayoutId: String)
}

