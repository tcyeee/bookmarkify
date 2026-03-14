package top.tcyeee.bookmarkify.controller.bookmark

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import top.tcyeee.bookmarkify.entity.WebsiteInfoVO
import top.tcyeee.bookmarkify.server.IApiService

/**
 * Standalone MockMvc test — no Spring context, no Sa-Token interceptor.
 * Tests controller request mapping, param binding, and response serialization.
 */
@Suppress("unused")
class ApiControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var apiService: IApiService

    @BeforeEach
    fun setup() {
        apiService = mock(IApiService::class.java)
        mockMvc = MockMvcBuilders
            .standaloneSetup(ApiController(apiService))
            .build()
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    fun `GET website-info returns 200 with all fields`() {
        val vo = WebsiteInfoVO(
            title = "GitHub",
            description = "Where the world builds software",
            siteName = "GitHub",
            canonicalUrl = "https://github.com",
            thumbnailUrl = "https://example.com/og.png",
            iconUrl = "https://github.com/favicon.ico",
            author = "GitHub",
            medium = "website",
        )
        `when`(apiService.queryWebsiteInfo("github.com")).thenReturn(vo)

        mockMvc.perform(get("/api/website-info").param("domain", "github.com"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.title").value("GitHub"))
            .andExpect(jsonPath("$.description").value("Where the world builds software"))
            .andExpect(jsonPath("$.siteName").value("GitHub"))
            .andExpect(jsonPath("$.canonicalUrl").value("https://github.com"))
            .andExpect(jsonPath("$.thumbnailUrl").value("https://example.com/og.png"))
            .andExpect(jsonPath("$.iconUrl").value("https://github.com/favicon.ico"))
            .andExpect(jsonPath("$.author").value("GitHub"))
            .andExpect(jsonPath("$.medium").value("website"))
    }

    @Test
    fun `GET website-info with null optional fields returns 200`() {
        `when`(apiService.queryWebsiteInfo("example.com"))
            .thenReturn(WebsiteInfoVO(title = "Example"))

        mockMvc.perform(get("/api/website-info").param("domain", "example.com"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("Example"))
            .andExpect(jsonPath("$.thumbnailUrl").doesNotExist())
            .andExpect(jsonPath("$.iconUrl").doesNotExist())
    }

    // ── parameter validation ──────────────────────────────────────────────────

    @Test
    fun `GET website-info without domain param returns 400`() {
        mockMvc.perform(get("/api/website-info"))
            .andExpect(status().isBadRequest)
    }

    // ── service delegation ────────────────────────────────────────────────────

    @Test
    fun `GET website-info delegates to apiService with correct domain`() {
        `when`(apiService.queryWebsiteInfo("bilibili.com"))
            .thenReturn(WebsiteInfoVO(title = "哔哩哔哩"))

        mockMvc.perform(get("/api/website-info").param("domain", "bilibili.com"))
            .andExpect(status().isOk)

        verify(apiService).queryWebsiteInfo("bilibili.com")
    }
}
