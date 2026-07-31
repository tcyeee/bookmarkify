package top.tcyeee.bookmarkify.entity.entity

import top.tcyeee.bookmarkify.entity.dto.BookmarkUrlWrapper
import top.tcyeee.bookmarkify.entity.enums.BookmarkLockedField
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 校验 [BookmarkEntity.checkFlag] 的过期判定。
 *
 * 这里唯一值得测的是**边界**：原实现用 `ChronoUnit.DAYS.between(...) > 1`，而 DAYS.between
 * 向下取整，于是「距上次解析 1.9 天」算出来是 1、判定为不过期——真实阈值悄悄变成了满 2 天，
 * 与代码里一直写着的「超过 1 天」差了一倍。用小时比较即可消除这个截断。
 */
class BookmarkEntityTest {

    private fun bookmarkParsedAt(updateTime: LocalDateTime?) = BookmarkEntity(
        BookmarkUrlWrapper(
            urlRaw = "https://example.com/",
            urlScheme = "https",
            urlHost = "example.com",
            urlRoot = "https://example.com",
            urlFull = "https://example.com/",
            urlPath = "/",
            urlQuery = "",
        ),
        siteId = "site-example",
    ).apply { this.updateTime = updateTime }

    @Test
    fun `never parsed always needs a recheck`() {
        assertTrue(bookmarkParsedAt(null).checkFlag())
    }

    @Test
    fun `just parsed does not need a recheck`() {
        assertFalse(bookmarkParsedAt(LocalDateTime.now()).checkFlag())
    }

    @Test
    fun `still fresh just under the 24h boundary`() {
        assertFalse(bookmarkParsedAt(LocalDateTime.now().minusHours(23)).checkFlag())
    }

    @Test
    fun `stale once 24h have elapsed`() {
        assertTrue(bookmarkParsedAt(LocalDateTime.now().minusHours(24).minusMinutes(1)).checkFlag())
    }

    /** 截断 bug 的回归用例：1.5 天在旧实现里算 1 天、被判为「不过期」。 */
    @Test
    fun `a day and a half is stale, not rounded down to one day`() {
        assertTrue(bookmarkParsedAt(LocalDateTime.now().minusHours(36)).checkFlag())
    }

    // ────── 字段级人工锁定 ──────

    @Test
    fun `no locks by default`() {
        val bookmark = bookmarkParsedAt(null)
        assertTrue(bookmark.lockedFieldSet.isEmpty())
        assertFalse(bookmark.isLocked(BookmarkLockedField.TITLE))
    }

    @Test
    fun `lock is idempotent and serialises to a comma separated column`() {
        val bookmark = bookmarkParsedAt(null).apply {
            lock(BookmarkLockedField.TITLE)
            lock(BookmarkLockedField.TITLE)
            lock(BookmarkLockedField.APP_NAME)
        }
        assertEquals(setOf(BookmarkLockedField.TITLE, BookmarkLockedField.APP_NAME), bookmark.lockedFieldSet)
        // 逗号拼接，且不能因为重复加锁就写出 'TITLE,TITLE'
        assertEquals(2, bookmark.lockedFields!!.split(',').size)
    }

    @Test
    fun `unlocking the last field stores NULL rather than an empty string`() {
        // 两种「没有锁」的表示会让后续的查询与判空都要各写一遍
        val bookmark = bookmarkParsedAt(null).apply {
            lock(BookmarkLockedField.TITLE)
            unlock(BookmarkLockedField.TITLE)
        }
        assertNull(bookmark.lockedFields)
    }

    @Test
    fun `unlocking one field keeps the others`() {
        val bookmark = bookmarkParsedAt(null).apply {
            lock(BookmarkLockedField.TITLE, BookmarkLockedField.DESCRIPTION)
            unlock(BookmarkLockedField.TITLE)
        }
        assertEquals(setOf(BookmarkLockedField.DESCRIPTION), bookmark.lockedFieldSet)
    }

    /** 脏数据不能把整条书签的解析拖崩：无法识别的取值静默忽略。 */
    @Test
    fun `unknown values in the column are ignored`() {
        val bookmark = bookmarkParsedAt(null).apply { lockedFields = "TITLE, WHAT_IS_THIS ,,APP_NAME" }
        assertEquals(setOf(BookmarkLockedField.TITLE, BookmarkLockedField.APP_NAME), bookmark.lockedFieldSet)
    }
}
