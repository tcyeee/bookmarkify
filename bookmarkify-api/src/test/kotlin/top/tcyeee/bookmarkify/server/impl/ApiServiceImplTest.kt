package top.tcyeee.bookmarkify.server.impl

import cn.hutool.http.HttpRequest
import cn.hutool.http.HttpResponse
import cn.hutool.http.HttpUtil
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.`when`
import top.tcyeee.bookmarkify.config.entity.IframelyConfig
import top.tcyeee.bookmarkify.config.exception.CommonException
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Suppress("unused")
class ApiServiceImplTest {

    private val config = IframelyConfig(apiKey = "test-key")
    private val service = ApiServiceImpl(config)

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun buildMockChain(responseBody: String): Triple<MockedStaticWrapper, HttpRequest, HttpResponse> {
        val httpRequest = mock(HttpRequest::class.java)
        val httpResponse = mock(HttpResponse::class.java)

        `when`(httpRequest.form(anyString(), any(Any::class.java))).thenReturn(httpRequest)
        `when`(httpRequest.timeout(anyInt())).thenReturn(httpRequest)
        `when`(httpRequest.execute()).thenReturn(httpResponse)
        `when`(httpResponse.body()).thenReturn(responseBody)

        val staticMock: org.mockito.MockedStatic<HttpUtil> = mockStatic(HttpUtil::class.java)
        staticMock.`when`<HttpRequest> { HttpUtil.createGet(anyString()) }.thenReturn(httpRequest)

        return Triple(MockedStaticWrapper(staticMock), httpRequest, httpResponse)
    }

    // ── URL building ─────────────────────────────────────────────────────────

    @Test
    fun `should prepend https when domain has no scheme`() {
        val (wrapper, httpRequest) = buildMockChain(MINIMAL_JSON)
        val captor = ArgumentCaptor.forClass(Any::class.java)

        wrapper.use {
            service.queryWebsiteInfo("example.com")
            org.mockito.Mockito.verify(httpRequest).form(eq("url"), captor.capture())
            assertEquals("https://example.com", captor.value)
        }
    }

    @Test
    fun `should keep https scheme when already present`() {
        val (wrapper, httpRequest) = buildMockChain(MINIMAL_JSON)
        val captor = ArgumentCaptor.forClass(Any::class.java)

        wrapper.use {
            service.queryWebsiteInfo("https://example.com")
            org.mockito.Mockito.verify(httpRequest).form(eq("url"), captor.capture())
            assertEquals("https://example.com", captor.value)
        }
    }

    @Test
    fun `should keep http scheme when already present`() {
        val (wrapper, httpRequest) = buildMockChain(MINIMAL_JSON)
        val captor = ArgumentCaptor.forClass(Any::class.java)

        wrapper.use {
            service.queryWebsiteInfo("http://example.com")
            org.mockito.Mockito.verify(httpRequest).form(eq("url"), captor.capture())
            assertEquals("http://example.com", captor.value)
        }
    }

    // ── response parsing ─────────────────────────────────────────────────────

    @Test
    fun `should parse full iframely response correctly`() {
        val json = """
            {
              "url": "https://github.com",
              "meta": {
                "title": "GitHub",
                "description": "Where the world builds software",
                "site": "GitHub",
                "canonical": "https://github.com",
                "author": "GitHub",
                "medium": "website"
              },
              "links": {
                "thumbnail": [{"href": "https://example.com/og.png", "rel": ["thumbnail"]}],
                "icon": [{"href": "https://github.com/favicon.ico", "rel": ["icon"]}]
              }
            }
        """.trimIndent()

        buildMockChain(json).first.use {
            val vo = service.queryWebsiteInfo("github.com")
            assertEquals("GitHub", vo.title)
            assertEquals("Where the world builds software", vo.description)
            assertEquals("GitHub", vo.siteName)
            assertEquals("https://github.com", vo.canonicalUrl)
            assertEquals("https://example.com/og.png", vo.thumbnailUrl)
            assertEquals("https://github.com/favicon.ico", vo.iconUrl)
            assertEquals("GitHub", vo.author)
            assertEquals("website", vo.medium)
        }
    }

    @Test
    fun `should fall back to top-level url when meta canonical is absent`() {
        val json = """
            {
              "url": "https://example.com",
              "meta": {"title": "Example"},
              "links": {}
            }
        """.trimIndent()

        buildMockChain(json).first.use {
            val vo = service.queryWebsiteInfo("example.com")
            assertEquals("https://example.com", vo.canonicalUrl)
        }
    }

    @Test
    fun `should return null thumbnail and icon when links arrays are empty`() {
        buildMockChain(MINIMAL_JSON).first.use {
            val vo = service.queryWebsiteInfo("example.com")
            assertNull(vo.thumbnailUrl)
            assertNull(vo.iconUrl)
        }
    }

    @Test
    fun `should return null fields when meta object is absent`() {
        val json = """{"url": "https://example.com", "links": {}}"""

        buildMockChain(json).first.use {
            val vo = service.queryWebsiteInfo("example.com")
            assertNull(vo.title)
            assertNull(vo.description)
            assertNull(vo.siteName)
            assertNull(vo.author)
            assertNull(vo.medium)
        }
    }

    // ── error handling ────────────────────────────────────────────────────────

    @Test
    fun `should throw CommonException when iframely returns error field`() {
        val json = """{"error": 404, "message": "Not found"}"""

        buildMockChain(json).first.use {
            assertThrows<CommonException> {
                service.queryWebsiteInfo("notfound.example.com")
            }
        }
    }

    @Test
    fun `should throw CommonException on network failure`() {
        val httpRequest = mock(HttpRequest::class.java)
        `when`(httpRequest.form(anyString(), any(Any::class.java))).thenReturn(httpRequest)
        `when`(httpRequest.timeout(anyInt())).thenReturn(httpRequest)
        `when`(httpRequest.execute()).thenThrow(RuntimeException("Connection refused"))

        val staticMock2: org.mockito.MockedStatic<HttpUtil> = mockStatic(HttpUtil::class.java)
        staticMock2.use { staticMock ->
            staticMock.`when`<HttpRequest> { HttpUtil.createGet(anyString()) }.thenReturn(httpRequest)
            assertThrows<CommonException> {
                service.queryWebsiteInfo("unreachable.example.com")
            }
        }
    }

    // ── constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val MINIMAL_JSON = """{"url": "https://example.com", "meta": {}, "links": {}}"""
    }
}

/** Thin AutoCloseable wrapper so MockedStatic can be used in `use {}` via destructuring. */
private class MockedStaticWrapper(private val mock: org.mockito.MockedStatic<HttpUtil>) : AutoCloseable {
    override fun close() = mock.close()
}

