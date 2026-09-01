package top.tcyeee.bookmarkify.db

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * 数据库层面的**并发与约束**保证 —— 也就是那些"只有数据库做得到、应用层做不到"的事。
 *
 * ## 覆盖范围
 *
 * 现在只剩 `uk_page_canonical`（canonical 页面一页一行的并发收敛）和 `insertNodeAndLink` 的
 * 事务原子性。`uk_bookmark_uid_page`（同一用户不能重复收藏同一页面）于 2026-08-31 随
 * 「允许重复收藏」一并删除 —— 用户桌面上现在可以有多个指向同一页面的磁贴，那几条测试也一并移除。
 *
 * 项目已有的用例几乎全是纯函数测试（`AssetRolePolicy` / `LivenessPolicy` / `SsrfGuard`…），
 * 覆盖的恰好是最不容易错的那部分。这个文件补的是另一头。
 *
 * ## 为什么是真的 PostgreSQL
 *
 * 这里验的每一条都用不了 H2：并发插入时的 `unique_violation`、`ON CONFLICT` 的行为、
 * 事务在约束冲突后进入 aborted 状态，都是 PostgreSQL 的具体语义。用一个"差不多的"
 * 数据库去验只会给出一个"差不多的"结论。
 *
 * 用 zonky 的嵌入式实例而不是 Testcontainers：后者要求本机有 Docker。
 *
 * ## DDL 从哪里来
 *
 * 逐字取自 `deploy/schema.sql`（那是 schema of record）。**刻意不做简化**：把索引谓词抄成
 * "差不多的样子"，测的就是另一个索引了。这也顺带让这个文件成为那条索引定义的第二个副本，
 * 改动 schema 时会在这里得到一次提醒。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("page / bookmark 的数据库约束")
class PageConstraintTest {

    private lateinit var pg: EmbeddedPostgres
    private lateinit var ds: DataSource

    @BeforeAll
    fun startDatabase() {
        pg = EmbeddedPostgres.start()
        ds = pg.postgresDatabase
        ds.connection.use { c ->
            c.createStatement().use { st ->
                // ── 逐字取自 deploy/schema.sql，只保留本测试用得到的列 ──
                st.execute(
                    """
                    CREATE TABLE page (
                        id            varchar(40)  NOT NULL PRIMARY KEY,
                        site_id       varchar(40)  NOT NULL,
                        url_host      varchar(200) NOT NULL,
                        url_scheme    varchar(10)  NOT NULL,
                        url_path      varchar(500)  DEFAULT '/' NOT NULL,
                        url_query     varchar(1000) DEFAULT ''  NOT NULL,
                        url_fragment  varchar(500)  DEFAULT ''  NOT NULL,
                        parse_status  varchar(20)   DEFAULT 'PENDING' NOT NULL,
                        create_time   timestamp     DEFAULT now() NOT NULL
                    );
                    """.trimIndent()
                )
                st.execute(
                    """
                    CREATE TABLE bookmark (
                        id             varchar(40)  NOT NULL PRIMARY KEY,
                        uid            varchar(40)  NOT NULL,
                        page_id        varchar(40),
                        layout_node_id varchar(40)  NOT NULL,
                        url_full       varchar(1000) NOT NULL,
                        create_time    timestamp     DEFAULT now() NOT NULL,
                        deleted        boolean       DEFAULT false NOT NULL
                    );
                    """.trimIndent()
                )
                st.execute(
                    """
                    CREATE TABLE user_layout_node (
                        id   varchar(40) NOT NULL PRIMARY KEY,
                        uid  varchar(40) NOT NULL
                    );
                    """.trimIndent()
                )
                // ⚠️ 这条索引的定义必须与 deploy/schema.sql 逐字一致
                st.execute(
                    "CREATE UNIQUE INDEX uk_page_canonical ON page USING btree (site_id, url_path, url_query, url_fragment)"
                )
            }
        }
    }

    @AfterAll
    fun stopDatabase() = pg.close()

    // ────── 工具 ──────

    private fun <T> withConnection(block: (Connection) -> T): T = ds.connection.use(block)

    private fun insertBookmark(c: Connection, id: String, uid: String, pageId: String?, deleted: Boolean = false) {
        c.prepareStatement(
            "INSERT INTO bookmark (id, uid, page_id, layout_node_id, url_full, deleted) VALUES (?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, uid); ps.setString(3, pageId)
            ps.setString(4, "node-$id"); ps.setString(5, "https://example.com/$id"); ps.setBoolean(6, deleted)
            ps.executeUpdate()
        }
    }

    private fun insertPage(c: Connection, id: String, siteId: String, path: String, query: String = "", frag: String = "") {
        c.prepareStatement(
            "INSERT INTO page (id, site_id, url_host, url_scheme, url_path, url_query, url_fragment) VALUES (?,?,?,?,?,?,?)"
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, siteId); ps.setString(3, "example.com")
            ps.setString(4, "https"); ps.setString(5, path); ps.setString(6, query); ps.setString(7, frag)
            ps.executeUpdate()
        }
    }

    /**
     * 让 [n] 个线程尽可能同时跑 [action]，返回其中失败的异常。
     *
     * 用闩锁对齐起跑线而不是直接 `submit` 了事：后者在小批量下经常退化成串行执行，
     * 于是"并发双插"测出来的其实是"先后双插"。
     */
    private fun raceAndCollectFailures(n: Int, action: (Int) -> Unit): List<Throwable> {
        val pool = Executors.newFixedThreadPool(n)
        val start = CountDownLatch(1)
        val done = CountDownLatch(n)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        repeat(n) { i ->
            pool.submit {
                start.await()
                runCatching { action(i) }.onFailure(failures::add)
                done.countDown()
            }
        }
        start.countDown()
        check(done.await(30, TimeUnit.SECONDS)) { "并发任务未在 30s 内完成" }
        pool.shutdown()
        return failures.toList()
    }

    // ────── 允许重复收藏：同一用户可以多次收藏同一页面 ──────

    @Test
    @DisplayName("同一用户并发收藏同一页面，全部落地（不再判重）")
    fun `concurrent duplicate links all succeed`() {
        val uid = "u-race"
        val pageId = "p-race"

        val failures = raceAndCollectFailures(8) { i ->
            withConnection { c -> insertBookmark(c, "b-race-$i", uid, pageId) }
        }

        assertThat(failures).describedAs("uk_bookmark_uid_page 已删除，重复收藏不再冲突").isEmpty()
        val count = withConnection { c ->
            c.prepareStatement("SELECT count(*) FROM bookmark WHERE uid = ? AND page_id = ?").use { ps ->
                ps.setString(1, uid); ps.setString(2, pageId)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        assertThat(count).isEqualTo(8)
    }

    // ────── canonical 收敛：uk_page_canonical ──────

    @Test
    @DisplayName("并发创建同一 canonical 页面，只落一行")
    fun `concurrent canonical page creation converges`() {
        val siteId = "s-race"
        val failures = raceAndCollectFailures(8) { i ->
            withConnection { c -> insertPage(c, "pg-race-$i", siteId, "/watch") }
        }
        val count = withConnection { c ->
            c.prepareStatement("SELECT count(*) FROM page WHERE site_id = ? AND url_path = '/watch'").use { ps ->
                ps.setString(1, siteId)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        assertThat(count)
            .describedAs("getOrCreateByUrl 正是靠这条索引收敛并发插入；多于一行意味着同一个网址会分裂成两条 page 记录")
            .isEqualTo(1)
        assertThat(failures).hasSize(7)
    }

    @Test
    @DisplayName("四元组的每一维都参与去重——少一维就会把不同页面合并成一条")
    fun `canonical key spans all four columns`() {
        val siteId = "s-quad"
        withConnection { c ->
            insertPage(c, "q-1", siteId, "/a")
            insertPage(c, "q-2", siteId, "/b")                       // path 不同
            insertPage(c, "q-3", siteId, "/a", query = "v=1")        // query 不同
            insertPage(c, "q-4", siteId, "/a", frag = "sec")         // fragment 不同
            insertPage(c, "q-5", "s-other", "/a")                    // site 不同
        }
        val count = withConnection { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM page WHERE id LIKE 'q-%'").use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        assertThat(count).isEqualTo(5)
    }

    // ────── 事务边界：insertNodeAndLink ──────

    @Test
    @DisplayName("布局节点与用户关联必须同生共死——第二条失败时第一条不能留下")
    fun `layout node and link insert is atomic`() {
        val uid = "u-tx"
        // 先占住 bookmark 主键，让下面那次 INSERT 必然撞 PK 冲突
        withConnection { c -> insertBookmark(c, "b-tx-dup", uid, "p-tx") }

        val failure = runCatching {
            withConnection { c ->
                c.autoCommit = false
                try {
                    c.prepareStatement("INSERT INTO user_layout_node (id, uid) VALUES (?, ?)").use { ps ->
                        ps.setString(1, "node-orphan"); ps.setString(2, uid); ps.executeUpdate()
                    }
                    // 这一条撞主键
                    insertBookmark(c, "b-tx-dup", uid, "p-tx")
                    c.commit()
                } catch (e: Exception) {
                    c.rollback(); throw e
                }
            }
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SQLException::class.java)

        val orphanNodes = withConnection { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM user_layout_node WHERE id = 'node-orphan'")
                    .use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        // insertNodeAndLink 的注释：分开写时第二条失败会在用户桌面上留下一个没有任何书签数据的
        // 孤儿节点 —— layout() 按 layoutNodeId 找不到对应的 BookmarkShow，前端只能渲染出一个
        // 点不开也删不掉的空格子
        assertThat(orphanNodes)
            .describedAs("事务回滚后不该留下孤儿布局节点")
            .isEqualTo(0)
    }
}
