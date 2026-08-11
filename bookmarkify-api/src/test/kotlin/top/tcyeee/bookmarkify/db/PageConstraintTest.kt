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
 * ## 为什么非补不可
 *
 * `insertNodeAndLink` 的注释把这件事说得很清楚：
 *
 * > 唯一键冲突翻成 E126，**这里才是判重的权威**。上游的 `assertNotAlreadyLinked` 是
 * > check-then-act：查一次、再插入，两个并发请求可以同时通过那道检查。此前真正挡住重复磁贴的
 * > 其实是 `addOne` 上那个 1 秒的 `@Throttle` —— 而限流是 UX 设施不是正确性设施。
 *
 * 这个推理完全正确。问题是它**只活在注释里**：没有任何测试证明 `uk_bookmark_uid_page` 确实存在、
 * 谓词确实是那几个条件、并发双插确实只活一条。而这个索引是**手工应用**的迁移
 * （`deploy/migrations/`，没有 Flyway，部署流程也不跑迁移），所以"某个环境上它压根没建"
 * 是一个完全现实的状态 —— 那种环境下代码照常跑，只是重复磁贴又回来了，且没有任何报错。
 *
 * 项目已有的 222 个用例全部是纯函数测试（`AssetRolePolicy` / `LivenessPolicy` / `SsrfGuard`…），
 * 覆盖的恰好是最不容易错的那部分。这个文件补的是另一头。
 *
 * ## 为什么是真的 PostgreSQL
 *
 * 这里验的每一条都用不了 H2：**部分唯一索引的 `WHERE` 谓词**、并发插入时的
 * `unique_violation`、`ON CONFLICT` 的行为，都是 PostgreSQL 的具体语义。用一个"差不多的"
 * 数据库去验只会给出一个"差不多的"结论，而这几条约束的全部价值就在于它们是确定的。
 *
 * 用 zonky 的嵌入式实例而不是 Testcontainers：后者要求本机有 Docker。这套约束的验证不该被
 * "开发机上装没装 Docker"挡住 —— 一个跑不起来的测试等于没有测试。
 *
 * ## DDL 从哪里来
 *
 * 逐字取自 `deploy/schema.sql`（那是 schema of record）。**刻意不做简化**：把索引谓词抄成
 * "差不多的样子"，测的就是另一个索引了。这也顺带让这个文件成为那两条索引定义的第二个副本，
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
                // ⚠️ 这两条索引的定义必须与 deploy/schema.sql 逐字一致
                st.execute(
                    "CREATE UNIQUE INDEX uk_bookmark_uid_page ON bookmark USING btree (uid, page_id) " +
                        "WHERE ((deleted = false) AND (page_id IS NOT NULL) AND ((page_id)::text <> 'LOADING'::text))"
                )
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

    private fun countBookmarks(uid: String, pageId: String): Int = withConnection { c ->
        c.prepareStatement("SELECT count(*) FROM bookmark WHERE uid = ? AND page_id = ? AND deleted = false").use { ps ->
            ps.setString(1, uid); ps.setString(2, pageId)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }

    /**
     * 让 [n] 个线程尽可能同时跑 [action]，返回其中失败的异常。
     *
     * 用闩锁对齐起跑线而不是直接 `submit` 了事：后者在小批量下经常退化成串行执行，
     * 于是"并发双插"测出来的其实是"先后双插" —— 那两件事在这里恰好是不同的判据。
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

    // ────── 判重的权威：uk_bookmark_uid_page ──────

    @Test
    @DisplayName("同一用户并发收藏同一页面，只有一条能活下来")
    fun `concurrent duplicate links converge on the unique index`() {
        val uid = "u-race"
        val pageId = "p-race"

        val failures = raceAndCollectFailures(8) { i ->
            withConnection { c -> insertBookmark(c, "b-race-$i", uid, pageId) }
        }

        // 这正是 assertNotAlreadyLinked 那道 check-then-act 拦不住的情形：
        // 8 个请求可以同时读到"还没收藏过"，然后 8 个都去插
        assertThat(countBookmarks(uid, pageId))
            .describedAs("并发插入之后应只剩一条 —— 多于一条意味着 uk_bookmark_uid_page 没生效，用户桌面上会出现重复磁贴")
            .isEqualTo(1)
        assertThat(failures).hasSize(7)
        // 应用层把这个异常翻成 E126，判据是它必须是唯一键冲突而不是别的什么错
        assertThat(failures).allSatisfy { e ->
            assertThat(e).isInstanceOf(SQLException::class.java)
            assertThat((e as SQLException).sqlState)
                .describedAs("必须是 unique_violation(23505)，insertNodeAndLink 的 DuplicateKeyException 分支据此翻成 E126")
                .isEqualTo("23505")
        }
    }

    @Test
    @DisplayName("索引谓词的三个例外都必须成立：LOADING 占位、软删行、NULL page_id")
    fun `partial index predicate exempts placeholders and soft deleted rows`() {
        val uid = "u-exempt"
        withConnection { c ->
            // 1. 批量导入的占位：page_id 是字符串常量 'LOADING'，同一用户可以同时挂几千条。
            //    谓词里那句 `page_id <> 'LOADING'` 漏掉的话，导入第二条就会直接失败
            insertBookmark(c, "b-load-1", uid, "LOADING")
            insertBookmark(c, "b-load-2", uid, "LOADING")
            insertBookmark(c, "b-load-3", uid, "LOADING")

            // 2. page_id 为 NULL 的行同样不参与判重
            insertBookmark(c, "b-null-1", uid, null)
            insertBookmark(c, "b-null-2", uid, null)

            // 3. 软删：删掉再加回来必须能成功。`deleted = false` 这个条件漏掉的话，
            //    用户删过一次的书签就再也加不回来了 —— 而且报的是"重复收藏"
            insertBookmark(c, "b-soft-old", uid, "p-soft", deleted = true)
            insertBookmark(c, "b-soft-new", uid, "p-soft", deleted = false)
        }
        assertThat(countBookmarks(uid, "p-soft")).isEqualTo(1)
    }

    @Test
    @DisplayName("软删一条之后重新收藏同一页面不该冲突，但活着的仍只能有一条")
    fun `re-adding after soft delete works but still allows only one live row`() {
        val uid = "u-resurrect"
        withConnection { c -> insertBookmark(c, "b-r1", uid, "p-r") }
        withConnection { c ->
            c.prepareStatement("UPDATE bookmark SET deleted = true WHERE id = 'b-r1'").use { it.executeUpdate() }
        }
        withConnection { c -> insertBookmark(c, "b-r2", uid, "p-r") }
        assertThat(countBookmarks(uid, "p-r")).isEqualTo(1)

        // 再插第三条就该被挡住了
        assertThat(runCatching { withConnection { c -> insertBookmark(c, "b-r3", uid, "p-r") } }.exceptionOrNull())
            .isInstanceOf(SQLException::class.java)
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
        // 先占住 (uid, page_id)，让下面那次插入必然冲突
        withConnection { c -> insertBookmark(c, "b-tx-seed", uid, "p-tx") }

        val failure = runCatching {
            withConnection { c ->
                c.autoCommit = false
                try {
                    c.prepareStatement("INSERT INTO user_layout_node (id, uid) VALUES (?, ?)").use { ps ->
                        ps.setString(1, "node-orphan"); ps.setString(2, uid); ps.executeUpdate()
                    }
                    // 这一条撞唯一索引
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
