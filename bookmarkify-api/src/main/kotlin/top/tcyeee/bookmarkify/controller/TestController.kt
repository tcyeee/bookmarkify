//package top.tcyeee.bookmarkify.controller
//
//import cn.dev33.satoken.annotation.SaIgnore
//import io.swagger.v3.oas.annotations.tags.Tag
//import org.springframework.web.bind.annotation.GetMapping
//import org.springframework.web.bind.annotation.RequestMapping
//import org.springframework.web.bind.annotation.RestController
//import top.tcyeee.bookmarkify.entity.entity.BookmarkEntity
//import top.tcyeee.bookmarkify.server.IBookmarkService
//import top.tcyeee.bookmarkify.server.IKafkaMessageService
//
//
///**
// * 测试类
// *
// * @author tcyeee
// * @date 3/9/25 19:53
// */
//@RestController
//@Tag(name = "测试相关")
//@RequestMapping("/test")
//class TestController(
//    private val messageService: IKafkaMessageService,
//    private val bookmarkService: IBookmarkService,
//) {
//
//    @SaIgnore
//    @GetMapping("/link")
//    fun linkTest(type: Int): Boolean {
//        if (type == 1) return this.kafkaTest()
//        if (type == 2) return this.bookmarkParseAndNotice()
//        return true
//    }
//
//    private fun kafkaTest(): Boolean {
//        messageService.linkTest("Hello World")
//        return true
//    }
//
//    private fun bookmarkParseAndNotice():Boolean {
//        BookmarkEntity(
//            id = "dcc31fa4-c2d1-4c94-8ee3-a0f6b41c0222",
//            urlHost = "www.guokr.com",
//            urlPath = "",
//            urlScheme = "https",
//            maximalLogoSize = 0,
//            iconPadding = 0,
////            parseStatus = ,
//        )
//        bookmarkService.bookmarkParseAndNotice("4c9801bb-9a30-426a-b550-42b272ca93ad", bookmark, userLinkId, nodeLayoutId)
//    }
//}