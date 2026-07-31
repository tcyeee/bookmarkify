package top.tcyeee.bookmarkify

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import top.tcyeee.bookmarkify.config.entity.DeepSeekConfig
import top.tcyeee.bookmarkify.config.entity.ScrapperConfig
import top.tcyeee.bookmarkify.mapper.AiCallLogMapper
import top.tcyeee.bookmarkify.mapper.ScrapperCallLogMapper
import top.tcyeee.bookmarkify.server.impl.ApiServiceImpl

class SimilarSiteParseTest {
    private val svc = ApiServiceImpl(
        ScrapperConfig(), DeepSeekConfig(), ObjectMapper().registerKotlinModule(),
        mock(ScrapperCallLogMapper::class.java),
        mock(AiCallLogMapper::class.java),
    )

    @Test
    fun `parses plain json array`() {
        val raw = """[{"name":"知乎","domain":"zhihu.com","reason":"问答社区"}]"""
        val list = svc.parseSimilarSites(raw)
        assertEquals(1, list.size)
        assertEquals("zhihu.com", list[0].domain)
    }

    @Test
    fun `strips markdown code fence`() {
        val raw = "```json\n[{\"name\":\"A\",\"domain\":\"a.com\",\"reason\":\"r\"}]\n```"
        assertEquals(1, svc.parseSimilarSites(raw).size)
    }

    @Test
    fun `returns empty on garbage`() {
        assertTrue(svc.parseSimilarSites("not json at all").isEmpty())
    }
}
