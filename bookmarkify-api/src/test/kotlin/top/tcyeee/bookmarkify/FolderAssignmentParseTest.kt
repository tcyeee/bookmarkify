package top.tcyeee.bookmarkify

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import top.tcyeee.bookmarkify.config.entity.DeepSeekConfig
import top.tcyeee.bookmarkify.config.entity.ScrapperConfig
import top.tcyeee.bookmarkify.entity.dto.ReclassifyItem
import top.tcyeee.bookmarkify.mapper.AiCallLogMapper
import top.tcyeee.bookmarkify.mapper.ScrapperCallLogMapper
import top.tcyeee.bookmarkify.server.impl.ApiServiceImpl

class FolderAssignmentParseTest {
    private val svc = ApiServiceImpl(
        ScrapperConfig(), DeepSeekConfig(), ObjectMapper().registerKotlinModule(),
        mock(ScrapperCallLogMapper::class.java),
        mock(AiCallLogMapper::class.java),
    )

    private val items = listOf(
        ReclassifyItem("n0", "GitHub", null, "github.com"),
        ReclassifyItem("n1", "Figma", null, "figma.com"),
        ReclassifyItem("n2", "知乎", null, "zhihu.com"),
    )

    @Test
    fun `maps index to folder name`() {
        val raw = """[{"i":0,"folder":"开发"},{"i":1,"folder":"设计"},{"i":2,"folder":"社区"}]"""
        val out = svc.parseFolderAssignments(raw, items)
        assertEquals(mapOf("n0" to "开发", "n1" to "设计", "n2" to "社区"), out)
    }

    @Test
    fun `strips code fence and tolerates alternate keys`() {
        val raw = "```json\n[{\"index\":0,\"folderName\":\"开发\"}]\n```"
        assertEquals(mapOf("n0" to "开发"), svc.parseFolderAssignments(raw, items))
    }

    @Test
    fun `drops out-of-range indexes, blank names and duplicates`() {
        val raw = """[{"i":9,"folder":"X"},{"i":1,"folder":"  "},{"i":2,"folder":"社区"},{"i":2,"folder":"改主意"}]"""
        assertEquals(mapOf("n2" to "社区"), svc.parseFolderAssignments(raw, items))
    }

    @Test
    fun `returns empty on garbage`() {
        assertTrue(svc.parseFolderAssignments("not json", items).isEmpty())
    }
}
