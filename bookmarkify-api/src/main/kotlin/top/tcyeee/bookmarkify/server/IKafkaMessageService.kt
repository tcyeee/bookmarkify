package top.tcyeee.bookmarkify.server

interface IKafkaMessageService {

    /** 联通测试 */
    fun linkTest(message: String)

    /**
     * 通过网址解析为书签,同时重新绑定到添加这个网址的用户
     * 为什么要重新绑定？
     * 答: 用户添加网址的时候是批量添加的,只能提前批量返回用户自定义的书签,用户自定义的书签具体有没有存在源书签还不知道,所以查询完毕知道以后,再重新关联回去
     */
    fun bookmarkParseAndResetUserItem(uid: String, rawUrl: String, userLinkId: String, layoutNodeId: String)

    /**
     * 解析书签,然后保存到数据库
     * @param bookmarkId bookmark
     */
    fun bookmarkParse(bookmarkId: String)

    /**
     * 解析书签,保存书签到根节点,并通知到用户
     * @param uid user-id
     * @param bookmarkId bookmark-id
     * @param parentNodeId 父节点ID
     */
    fun bookmarkParseAndNotice(uid: String, bookmarkId: String, parentNodeId: String?)

    /**
     * 解析书签,然后保存到数据库,同时通知到用户
     * @param uid user-id
     * @param bookmarkId bookmark-id
     * @param userLinkId bookmark-user-link-id
     * @param nodeLayoutId 关联的桌面布局ID
     */
    fun bookmarkParseAndNotice(uid: String, bookmarkId: String, userLinkId: String, nodeLayoutId: String)
}

