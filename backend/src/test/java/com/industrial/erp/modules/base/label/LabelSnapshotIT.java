package com.industrial.erp.modules.base.label;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import com.industrial.erp.modules.base.entity.BaseLabelTemplateHist;
import com.industrial.erp.modules.base.mapper.BaseLabelTemplateHistMapper;
import com.industrial.erp.modules.base.mapper.BaseLabelTemplateMapper;
import com.industrial.erp.modules.base.service.BaseLabelTemplateService;
import com.industrial.erp.modules.system.entity.SysUser;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import com.industrial.erp.security.PermissionService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.ThrowableAssert.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 快照 + 串行更新 + 真实事务的集成证据 (docs/36 Task 6 Step 2 / Step 5)。
 *
 * <p>**为什么必须是真 MySQL：** 这一层的交付物是「一条 UPDATE 失败时，先前那条 INSERT 也要跟着消失」，
 * 以及「两个并发编辑必须拿到两个不同的 seq」。Mockito 桩不出来，H2 也模拟不出 InnoDB 的行锁、
 * {@code SELECT ... FOR UPDATE} 的当前读语义与 REPEATABLE READ 的 read view —— 这三样恰好就是
 * 本任务全部正确性的载体。所以本类跑在一次性容器里（见 {@link ThrowawayMysql}），由
 * {@code mvn verify} 的 failsafe 执行，{@code mvn test} 不碰它。
 *
 * <p>三条纪律：
 * <ol>
 *   <li>一律通过 **Spring 容器里的 service**（代理）调用；测试方法自己**不包事务** ——
 *       自己开事务就等于把被测的事务边界换成测试的；</li>
 *   <li>每个用例自己灌自己的小数据（固定小 id），互不依赖；</li>
 *   <li>断言已提交状态时用**新连接**读（{@link #queryCommitted}），绝不读服务自己那条还没提交的连接。</li>
 * </ol>
 */
@Tag("integration")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = LabelSnapshotTestConfig.class)
class LabelSnapshotIT {

    /** 静态初始化：类一加载就把容器起起来，早于 Spring 建上下文（{@code @DynamicPropertySource} 要读端口）。 */
    private static final ThrowawayMysql MYSQL = ThrowawayMysql.start();

    private static final String FORCED = "label_p1_forced_failure";
    private static final String TRIGGER = "label_p1_fail_update";

    @Autowired private BaseLabelTemplateService service;
    @Autowired private BaseLabelTemplateMapper templateMapper;
    @Autowired private BaseLabelTemplateHistMapper histMapper;
    @Autowired private PermissionService permService;
    @Autowired private SysUserMapper userMapper;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void jdbcProperties(DynamicPropertyRegistry registry) {
        registry.add("label.p1.mysql.url", MYSQL::jdbcUrl);
        registry.add("label.p1.mysql.user", () -> ThrowawayMysql.USER);
        registry.add("label.p1.mysql.password", () -> ThrowawayMysql.PASSWORD);
    }

    @AfterAll
    static void dropContainer() {
        MYSQL.close();
    }

    // ────────────────────────── 建库与小数据 ──────────────────────────

    /**
     * 每个用例前：建表（幂等 DDL）+ 清库 + 重置权限 mock。
     *
     * <p>主表用 **sql/155 的建表块原文**（只截 {@code CREATE TABLE IF NOT EXISTS
     * `base_label_template`} 到第一个半角分号），文件里那三条种子 INSERT 一行都不执行 —— 那是生产数据，
     * 灌进来只会让「hist 有几行」这种计数断言依赖外部因素。历史表则执行本次新增的 sql/158 **全文**。
     */
    @BeforeEach
    void prepareSchemaAndFixtures() throws Exception {
        try (Connection c = newConnection(); Statement st = c.createStatement()) {
            String mainDdl = mainTableDdlFrom155();
            assertThat(mainDdl).as("只许执行 sql/155 的建表块，不许带种子数据").doesNotContain("INSERT");
            st.execute(mainDdl);
            st.execute(readSqlFile("158_v11168_label_template_hist.sql"));
        }
        assertThat(histTableHasExpectedColumns()).as("sql/158 全文没把历史表建出来").isTrue();

        jdbc.update("DELETE FROM base_label_template_hist");
        jdbc.update("DELETE FROM base_label_template");

        reset(permService);
        // Task 7：一次性库里**没有 sys_user 表**（ThrowawayMysql 只建 sql/155/158 的表），
        // 姓名注入用 mock；默认空列表 = 「姓名缺失时沿用注入器回落、UI 还能显示 createBy」那条路。
        reset(userMapper);
        when(permService.hasPerm(BaseLabelTemplateService.PERM)).thenReturn(true);
        when(permService.getCurrentUserId()).thenReturn(42L);
        // 服务走的是 void 的 requirePerm(perm)，mock 的 void 方法默认什么都不做，
        // 那就等于「权限这一层根本没被授权、也没被检查」。按生产的语义把它接回 hasPerm，
        // 需要拒权的用例（hasPerm 返回 false）才会真的抛 403。
        Mockito.doAnswer(invocation -> {
            String perm = invocation.getArgument(0);
            if (!permService.hasPerm(perm)) {
                throw BizException.of(403, "无权限: " + perm);
            }
            return null;
        }).when(permService).requirePerm(BaseLabelTemplateService.PERM);

        // 事务语义是本类全部断言的前提：service 不是代理的话 @Transactional 等于没写，
        // 后面的回滚用例就会「绿」在一次根本没回滚的跑动上，所以先把这件事钉死。
        assertThat(AopUtils.isAopProxy(service))
                .as("service 必须是 Spring 代理，否则 update() 上的 @Transactional 不生效，整个 IT 失去意义")
                .isTrue();
    }

    private Connection newConnection() throws SQLException {
        Connection c = DriverManager.getConnection(MYSQL.jdbcUrl(), ThrowawayMysql.USER, ThrowawayMysql.PASSWORD);
        c.setAutoCommit(true);
        return c;
    }

    private boolean histTableHasExpectedColumns() throws SQLException {
        try (Connection c = newConnection();
             ResultSet rs = c.getMetaData().getColumns(null, ThrowawayMysql.DATABASE,
                     "base_label_template_hist", null)) {
            List<String> cols = new ArrayList<>();
            while (rs.next()) cols.add(rs.getString("COLUMN_NAME").toLowerCase());
            return cols.containsAll(List.of("id", "template_id", "seq", "biz_type", "width_mm", "height_mm",
                    "gap_mm", "dpi", "direction", "copies", "elements", "create_by", "create_time"));
        }
    }

    /** 从仓库根的 sql/ 取文件（failsafe 的 user.dir 是 backend/，往上级找一次即中）。 */
    private static Path repoSql(String fileName) {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (int up = 0; up <= 3 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve("sql").resolve(fileName);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("Task 6 集成测试被阻塞：找不到 sql/" + fileName
                + "（起始目录 " + System.getProperty("user.dir") + "）—— IT 必须跑在工作区里。");
    }

    private static String readSqlFile(String fileName) throws IOException {
        return Files.readString(repoSql(fileName), StandardCharsets.UTF_8);
    }

    /**
     * sql/155 的 base_label_template 建表块。
     *
     * <p>块内注释用的是**全角**「；」，所以第一个半角 {@code ;} 就是块尾；顺手断言截出来的这一段
     * 不含 INSERT，防止以后有人把种子数据插进同一个块里。
     */
    private static String mainTableDdlFrom155() throws IOException {
        String file = readSqlFile("155_v11167_label_tables.sql");
        int start = file.indexOf("CREATE TABLE IF NOT EXISTS `base_label_template` (");
        assertThat(start).as("sql/155 里必须还能找到 base_label_template 的建表块").isGreaterThanOrEqualTo(0);
        int end = file.indexOf(';', start);
        assertThat(end).as("建表块要以半角分号收尾").isGreaterThan(start);
        return file.substring(start, end + 1);
    }

    /** 固定小 id + 全部必填列：不依赖生产审计上下文，也不让 id 变成雪花值把断言写成一团乱。 */
    private void insertTemplate(long id, String code, String elements) {
        jdbc.update("INSERT INTO base_label_template (id, template_code, template_name, biz_type,"
                        + " width_mm, height_mm, gap_mm, dpi, direction, copies, elements, is_default,"
                        + " status, create_by, create_time, update_by, update_time, deleted)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                id, code, "测试卷标 " + code, "FG_ROLL", 80, 60, 3, 203, "NORMAL", 1,
                elements, 0, 1, 7L, LocalDateTime.of(2020, 1, 1, 0, 0), 7L,
                LocalDateTime.of(2020, 1, 1, 0, 0));
    }

    /**
     * 只有一个元素、只改 x 的一版内容。
     *
     * <p>纸头固定 80×60@203dpi = 640×480 点，size 24 的字最远只到 x=42，所以循环到 21 版也不会
     * 被闸以「越界」为由拦掉 —— 失败/成功必须只由被测机制决定，不能由测试数据自己出界决定。
     */
    private static String elementsAt(int x) {
        return "[{\"type\":\"text\",\"x\":" + x + ",\"y\":0,\"field\":\"batchNo\",\"size\":24}]";
    }

    /** 客户端 PUT 上来的新一版：纸头照旧，只有元素变，好让「历史抄的是哪一版」看得见。 */
    private BaseLabelTemplate payload(long id, String elements) {
        BaseLabelTemplate t = new BaseLabelTemplate();
        t.setId(id);
        t.setTemplateCode("FG_" + id);
        t.setTemplateName("测试卷标");
        t.setBizType("FG_ROLL");
        t.setWidthMm(80); t.setHeightMm(60); t.setGapMm(3); t.setDpi(203);
        t.setDirection("NORMAL"); t.setCopies(1);
        t.setElements(elements);
        return t;
    }

    // ────────────────────────── 新连接回读 ──────────────────────────

    /** 用一条**全新的**连接读已提交状态；服务自己那条连接里未提交的东西在这里看不见。 */
    private List<Map<String, Object>> queryCommitted(String sql, Object... args) throws SQLException {
        try (Connection c = newConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) row.put(md.getColumnLabel(i), rs.getObject(i));
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    private List<Integer> committedSeqs(long templateId) throws SQLException {
        List<Map<String, Object>> rows = queryCommitted(
                "SELECT seq FROM base_label_template_hist WHERE template_id=? ORDER BY seq", templateId);
        List<Integer> seqs = new ArrayList<>();
        for (Map<String, Object> r : rows) seqs.add(((Number) r.get("seq")).intValue());
        return seqs;
    }

    private int committedHistCount(long templateId) throws SQLException {
        return ((Number) queryCommitted("SELECT COUNT(*) AS n FROM base_label_template_hist"
                + " WHERE template_id=?", templateId).get(0).get("n")).intValue();
    }

    private String committedHistElements(long templateId, int seq) throws SQLException {
        return (String) queryCommitted("SELECT elements FROM base_label_template_hist"
                        + " WHERE template_id=? AND seq=?", templateId, seq).get(0).get("elements");
    }

    private String committedMainElements(long id) throws SQLException {
        return (String) queryCommitted("SELECT elements FROM base_label_template WHERE id=?", id)
                .get(0).get("elements");
    }

    private Map<String, Object> committedHistRow(long templateId, int seq) throws SQLException {
        return queryCommitted("SELECT * FROM base_label_template_hist WHERE template_id=? AND seq=?",
                templateId, seq).get(0);
    }

    private void installFailingTrigger() throws SQLException {
        try (Connection c = newConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TRIGGER " + TRIGGER + " BEFORE UPDATE ON base_label_template "
                    + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='" + FORCED + "'");
        }
    }

    /** 删的是**本临时测试库自己的** trigger；生产库上永远不该出现这个名字（brief Step 6）。 */
    private void dropFailingTriggerQuietly() {
        try (Connection c = newConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TRIGGER IF EXISTS " + TRIGGER);
        } catch (SQLException e) {
            throw new IllegalStateException("临时测试库的 trigger 没删干净会污染后续用例：" + e.getMessage(), e);
        }
    }

    private static void assertAnyCauseHas(Throwable thrown, String text) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(text)) return;
        }
        throw new AssertionError("异常链里找不到 " + text + "，实际：" + describe(thrown));
    }

    private static String describe(Throwable t) {
        if (t == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getClass().getName()).append(": ").append(c.getMessage()).append(" <- ");
        }
        return sb.toString();
    }

    // ────────────────────────── 真库基线 ──────────────────────────

    @Test
    @DisplayName("真库基线：MySQL 8、列名映射与三条手写锁 SQL 都真的跑得通，空历史 maxSeq 回 null")
    void contextTalksToARealMysql8() throws Exception {
        assertThat((String) queryCommitted("SELECT VERSION() AS v").get(0).get("v")).startsWith("8.");
        insertTemplate(101, "FG_101", elementsAt(0));

        // selectForUpdate 是手写 SELECT *：mapUnderscoreToCamelCase 没生效的话整行读成 null，
        // 后面所有用例都会「绿」在一条根本没读到旧行的路径上，所以先单独钉一次。
        BaseLabelTemplate locked = templateMapper.selectForUpdate(101L);
        assertThat(locked).isNotNull();
        assertThat(locked.getTemplateCode()).isEqualTo("FG_101");
        assertThat(locked.getBizType()).isEqualTo("FG_ROLL");
        assertThat(locked.getWidthMm()).isEqualTo(80);
        assertThat(locked.getElements()).isEqualTo(elementsAt(0));

        assertThat(histMapper.maxSeq(101L)).as("空历史必须回 null，由服务当 0 处理").isNull();
        assertThat(histMapper.retainedIds(101L)).as("空历史的保留集合是空列表而不是 null").isEmpty();
    }

    // ────────────────────────── Step 5 之一：快照真的落了库 ──────────────────────────

    @Test
    @DisplayName("经代理 update：主表变 B、hist 落一条 seq=1 的 A 快照，操作者 42 来自权限服务")
    void updateWritesOneCommittedSnapshotOfTheOldRow() throws Exception {
        String a = elementsAt(0);
        String b = elementsAt(10);
        insertTemplate(101, "FG_101", a);

        service.update(payload(101, b));

        assertThat(committedMainElements(101L)).isEqualTo(b);
        assertThat(committedSeqs(101L)).containsExactly(1);
        Map<String, Object> hist = committedHistRow(101L, 1);
        assertThat(hist.get("template_id")).isEqualTo(101L);
        assertThat(hist.get("seq")).isEqualTo(1);
        assertThat(hist.get("elements")).isEqualTo(a);
        assertThat(hist.get("biz_type")).isEqualTo("FG_ROLL");
        assertThat(hist.get("width_mm")).isEqualTo(80);
        assertThat(hist.get("height_mm")).isEqualTo(60);
        assertThat(hist.get("dpi")).isEqualTo(203);
        assertThat(hist.get("create_by")).as("hist 的操作者是当前用户，不是主表旧行的 create_by=7").isEqualTo(42L);
        assertThat(String.valueOf(hist.get("create_time"))).as("快照时间是本次更新时刻，不抄旧行 2020-01-01")
                .doesNotStartWith("2020");
    }

    // ────────────────────────── Step 5 之二：主表写失败必须整体回滚 ──────────────────────────

    @Test
    @DisplayName("trigger 让主表 UPDATE 失败：抛异常、主表仍 A、hist 行数 0（整体回滚）")
    void failedMainUpdateRollsBackTheSnapshot() throws Exception {
        String a = elementsAt(0);
        insertTemplate(101, "FG_101", a);
        installFailingTrigger();
        Throwable thrown;
        try {
            thrown = catchThrowable(() -> service.update(payload(101, elementsAt(10))));
        } finally {
            dropFailingTriggerQuietly();
        }

        assertThat(thrown).as("主表 UPDATE 被 trigger 拦下，update 必须失败而不是返回成功").isNotNull();
        assertAnyCauseHas(thrown, FORCED);
        assertThat(committedMainElements(101L)).as("主表必须仍是 A").isEqualTo(a);
        assertThat(committedHistCount(101L)).as("事务里那条 hist INSERT 必须跟着回滚").isZero();
    }

    @Test
    @DisplayName("同一 trigger、同一张表，**不带事务**地照抄写序会留下 1 条孤儿 hist —— 上面那条断言不是白写的")
    void nonTransactionalBaselineLeavesAnOrphanSnapshot() throws Exception {
        String a = elementsAt(0);
        insertTemplate(101, "FG_101", a);
        installFailingTrigger();
        try (Connection c = newConnection()) {
            c.setAutoCommit(true);                      // 就是「忘了加 @Transactional」的样子
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO base_label_template_hist (id, template_id, seq, biz_type,"
                                + " width_mm, height_mm, gap_mm, dpi, direction, copies, elements, create_by,"
                                + " create_time) VALUES (777001, 101, 1, 'FG_ROLL', 80, 60, 3, 203, 'NORMAL',"
                                + " 1, '" + a + "', 42, NOW())");
                assertThatThrownBy(() -> st.executeUpdate("UPDATE base_label_template SET elements='"
                                + elementsAt(10) + "' WHERE id=101"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining(FORCED);
            }
        } finally {
            dropFailingTriggerQuietly();
        }

        assertThat(committedMainElements(101L)).isEqualTo(a);
        assertThat(committedHistCount(101L))
                .as("没有事务时 INSERT 已经自动提交 —— 这正是 failedMainUpdateRollsBackTheSnapshot 要抓的形态")
                .isEqualTo(1);
    }

    // ────────────────────────── Step 5 之三：裁剪与「第 21 次失败」 ──────────────────────────

    @Test
    @DisplayName("连续 21 次成功更新：库里只剩 seq 2…21 共 20 条（用查回来的 seq/内容断言，不靠 SQL 字符串）")
    void twentyOneSuccessfulUpdatesKeepSeqTwoToTwentyOne() throws Exception {
        insertTemplate(101, "FG_101", elementsAt(0));

        for (int i = 1; i <= 21; i++) {
            service.update(payload(101, elementsAt(i * 2)));
        }

        List<Integer> expected = new ArrayList<>();
        for (int i = 2; i <= 21; i++) expected.add(i);
        assertThat(committedSeqs(101L)).containsExactlyElementsOf(expected);
        assertThat(committedHistCount(101L)).as("只留最近 20 版").isEqualTo(20);
        // 内容也对得上：seq2 = 第 2 次更新覆盖掉的 E(2)，seq21 = 第 21 次覆盖掉的 E(40)，
        // 于是「最早那版 A=E(0) 与 E(42) 之外的一切」都由 seq 列表自己证明，不需要去反射 SQL 文本。
        assertThat(committedHistElements(101L, 2)).isEqualTo(elementsAt(2));
        assertThat(committedHistElements(101L, 21)).isEqualTo(elementsAt(40));
        assertThat(committedMainElements(101L)).isEqualTo(elementsAt(42));
    }

    @Test
    @DisplayName("第 21 次更新失败：旧的 20 版一条不少、seq1（最早的 A）还在、主表停在第 20 次的结果")
    void failureAtTheTwentyFirstUpdateLeavesTheOldTwentyUntouched() throws Exception {
        String a = elementsAt(0);
        insertTemplate(101, "FG_101", a);
        for (int i = 1; i <= 20; i++) {
            service.update(payload(101, elementsAt(i * 2)));
        }
        String mainBefore = committedMainElements(101L);
        List<Integer> seqsBefore = committedSeqs(101L);
        assertThat(seqsBefore).hasSize(20);

        installFailingTrigger();
        Throwable thrown;
        try {
            thrown = catchThrowable(() -> service.update(payload(101, elementsAt(42))));
        } finally {
            dropFailingTriggerQuietly();
        }

        assertThat(thrown).isNotNull();
        assertAnyCauseHas(thrown, FORCED);
        assertThat(committedSeqs(101L)).as("失败的那次不许裁掉 seq1，也不许多出 seq21")
                .containsExactlyElementsOf(seqsBefore);
        assertThat(committedHistCount(101L)).isEqualTo(20);
        assertThat(committedHistElements(101L, 1)).as("最早那一版必须还在：裁剪被一起回滚了").isEqualTo(a);
        assertThat(committedMainElements(101L)).isEqualTo(mainBefore);
    }

    // ────────────────────────── Step 5 之四：并发串行化 ──────────────────────────

    @Test
    @DisplayName("两个线程同时编辑同一份模板：seq 恰好 1、2 不重复，seq1=A，seq2 与主表是两份不同内容")
    void concurrentEditorsSerializeOnTheRowLock() throws Exception {
        String a = elementsAt(0);
        String b = elementsAt(10);
        String c = elementsAt(20);
        insertTemplate(101, "FG_101", a);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            Future<?> first = pool.submit(() -> edit(startGate, 101L, b, failure));
            Future<?> second = pool.submit(() -> edit(startGate, 101L, c, failure));
            startGate.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(failure.get()).as("两个编辑都该成功（last-write-wins，不加乐观锁）：" + describe(failure.get()))
                .isNull();
        assertThat(committedSeqs(101L)).as("两次提交各拿一个 seq，不许撞号").containsExactly(1, 2);
        assertThat(committedHistElements(101L, 1)).as("seq1 一定是被覆盖前的 A").isEqualTo(a);
        String seq2 = committedHistElements(101L, 2);
        String main = committedMainElements(101L);
        assertThat(List.of(seq2, main)).as("先成功者的内容进历史，后提交者留在主表")
                .containsExactlyInAnyOrder(b, c);
        assertThat(seq2).isNotEqualTo(main);
    }

    private void edit(CountDownLatch startGate, long id, String elements,
                      AtomicReference<Throwable> failure) {
        try {
            startGate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure.set(e);
            return;
        }
        try {
            service.update(payload(id, elements));
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }

    @Test
    @DisplayName("确定性时序：后一个事务先在权限桩里读到「hist 还是空的」，之后仍编出 seq2 而不是重复 seq1")
    void seqUsesCurrentReadNotTheOlderSnapshot() throws Exception {
        String a = elementsAt(0);
        String b = elementsAt(10);
        String c = elementsAt(20);
        insertTemplate(101, "FG_101", a);

        CountDownLatch probeReadDone = new CountDownLatch(1);
        CountDownLatch holdLateEditor = new CountDownLatch(1);
        AtomicReference<Thread> lateEditor = new AtomicReference<>();
        AtomicLong rowsSeenByPlainRead = new AtomicLong(-1);
        AtomicReference<Throwable> lateFailure = new AtomicReference<>();

        // requirePerm 是服务里的第一步，也是「进主行锁之前」唯一的插针点：在这里对 hist 做一次
        // **普通** SELECT，本事务此前看到的就是「零版历史」，随后才去抢行锁、等第一个事务提交。
        // 只有当前读才拿得到刚提交的 seq1（普通读在 REPEATABLE READ 下会一直停在 0，
        // 机制证据见 repeatableReadSnapshotLagsButLockingReadDoesNot）。
        Mockito.doAnswer(invocation -> {
            if (Thread.currentThread() == lateEditor.get()) {
                rowsSeenByPlainRead.set(histMapper.selectCount(
                        new QueryWrapper<BaseLabelTemplateHist>().eq("template_id", 101L)));
                probeReadDone.countDown();
                holdLateEditor.await(20, TimeUnit.SECONDS);
            }
            return null;                       // 放行；hasPerm 此时为 true，与上面的委托等价
        }).when(permService).requirePerm(BaseLabelTemplateService.PERM);

        Thread t2 = new Thread(() -> {
            try {
                service.update(payload(101, c));
            } catch (Throwable t) {
                lateFailure.set(t);
            }
        }, "label-p1-late-editor");
        lateEditor.set(t2);
        t2.start();
        try {
            assertThat(probeReadDone.await(20, TimeUnit.SECONDS)).as("第二个事务没在权限桩里做旧快照读").isTrue();
            assertThat(rowsSeenByPlainRead.get()).as("旧快照里必须真的看不到历史").isZero();

            service.update(payload(101, b));            // 第一个事务完整提交
            assertThat(committedSeqs(101L)).containsExactly(1);
        } finally {
            holdLateEditor.countDown();
        }

        t2.join(TimeUnit.SECONDS.toMillis(20));
        assertThat(t2.isAlive()).as("第二个事务 20 秒内没跑完").isFalse();
        assertThat(lateFailure.get()).as("第二个事务不该失败：" + describe(lateFailure.get())).isNull();

        assertThat(committedSeqs(101L)).as("序号查询用的是倒序首行的锁定当前读，不是普通聚合快照读")
                .containsExactly(1, 2);
        assertThat(committedHistElements(101L, 1)).isEqualTo(a);
        assertThat(committedHistElements(101L, 2)).as("seq2 抄的是第一个事务刚落库的那一版").isEqualTo(b);
        assertThat(committedMainElements(101L)).isEqualTo(c);
    }

    @Test
    @DisplayName("机制证据：同一条会话在 REPEATABLE READ 下普通读拿不到别人刚提交的 seq，FOR UPDATE 拿得到")
    void repeatableReadSnapshotLagsButLockingReadDoesNot() throws Exception {
        insertTemplate(101, "FG_101", elementsAt(0));

        try (Connection c = newConnection()) {
            // 两步 SET 是给这条用例做**自检**的：一次性容器的默认级别本来就是 REPEATABLE READ，
            // 只设一次再读回来的话，「读回 RR」分不清是本用例设出来的还是继承默认值 —— 那样这个断言
            // 永远是绿的。先把它按到 RC（读回必须是 RC，证明 SET SESSION 对这条连接真的有效），
            // 再设回 RR，此时读回 RR 才只能是被设出来的。
            //
            // 两句都必须排在 setAutoCommit(false) **之前**：MySQL 的 SET TRANSACTION ISOLATION LEVEL
            // 只作用于下一个事务，事务已经开着的时候它就是条空语句（评审 Task 6 I-1）。
            try (Statement st = c.createStatement()) {
                st.execute("SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED");
            }
            assertThat(sessionIsolation(c)).as("前置自检：SET SESSION 必须真能改这条会话的级别，"
                    + "否则下面 REPEATABLE READ 的断言只是容器默认值").isEqualTo("READ-COMMITTED");
            try (Statement st = c.createStatement()) {
                st.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            }
            assertThat(sessionIsolation(c)).as("会话级别被本用例显式设成 REPEATABLE READ（与服务的 READ COMMITTED 相对）")
                    .isEqualTo("REPEATABLE-READ");

            c.setAutoCommit(false);

            // 事务里的第一次普通读把 read view 定在这里 —— 此时 hist 一条都没有。
            assertThat(plainMaxSeq(c, 101L)).as("read view 建立时确实没有历史").isNull();

            service.update(payload(101, elementsAt(10)));          // 另一个事务提交出 seq=1
            assertThat(committedSeqs(101L)).containsExactly(1);

            assertThat(plainMaxSeq(c, 101L)).as("REPEATABLE READ 的普通读还停在旧 read view 里")
                    .isNull();
            assertThat(lockingMaxSeq(c, 101L)).as("同一条会话、同一个事务，FOR UPDATE 却看得到刚提交的 seq1"
                    + " —— 这就是序号查询必须带 FOR UPDATE 的全部理由").isEqualTo(1);
            c.rollback();
        }
    }

    @Test
    @DisplayName("服务这次真的跑在 READ COMMITTED 上（从事务内的会话变量读出来，不是只看注解）")
    void updateTransactionActuallyRunsAtReadCommitted() throws Exception {
        insertTemplate(101, "FG_101", elementsAt(0));

        AtomicReference<String> isolationInsideTheTransaction = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
            // JdbcTemplate 取到的是 DataSourceUtils 绑在当前事务上的那条连接，所以这里的
            // @@transaction_isolation 就是 service.update 那个事务真正的会话级别。
            isolationInsideTheTransaction.set(jdbc.queryForMap("SELECT @@transaction_isolation AS iso")
                    .get("iso").toString());
            return null;
        }).when(permService).requirePerm(BaseLabelTemplateService.PERM);

        service.update(payload(101, elementsAt(10)));

        assertThat(isolationInsideTheTransaction.get()).as("update 的事务级别必须是 READ-COMMITTED，"
                + "否则不同模板的空档 gap 锁会和对方的 insert-intention 锁互卡成 1213 死锁")
                .isEqualTo("READ-COMMITTED");
        assertThat(committedSeqs(101L)).containsExactly(1);
    }

    @Test
    @DisplayName("两份模板同时更新：各自 seq 都从 1 开始，互不影响（seq 作用域是 template_id）")
    void differentTemplatesNumberIndependently() throws Exception {
        insertTemplate(101, "FG_101", elementsAt(0));
        insertTemplate(102, "FG_102", elementsAt(0));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            Future<?> t1 = pool.submit(() -> edit(startGate, 101L, elementsAt(10), failure));
            Future<?> t2 = pool.submit(() -> edit(startGate, 102L, elementsAt(20), failure));
            startGate.countDown();
            t1.get(10, TimeUnit.SECONDS);
            t2.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(failure.get()).as(describe(failure.get())).isNull();
        assertThat(committedSeqs(101L)).as("模板 101 自己的第一版").containsExactly(1);
        assertThat(committedSeqs(102L)).as("模板 102 也各自从 1 起，不被别的模板的序号推着走").containsExactly(1);
        assertThat(committedHistElements(101L, 1)).isEqualTo(elementsAt(0));
        assertThat(committedHistElements(102L, 1)).isEqualTo(elementsAt(0));
    }

    // ────────────────────────── 闸在快照之前（真库版） ──────────────────────────

    @Test
    @DisplayName("没过保存闸的 PUT：主表与 hist 都不许动（真库上验，不只是 Mockito 的 never()）")
    void gateRejectionWritesNothingOnRealMysql() throws Exception {
        String a = elementsAt(0);
        insertTemplate(101, "FG_101", a);

        BaseLabelTemplate bad = payload(101, "[{\"type\":\"text\",\"x\":1,\"y\":0,\"field\":\"nonsense\",\"size\":24}]");
        assertThatThrownBy(() -> service.update(bad)).isInstanceOf(Exception.class)
                .hasMessageContaining("nonsense");

        assertThat(committedMainElements(101L)).isEqualTo(a);
        assertThat(committedHistCount(101L)).isZero();
    }

    @Test
    @DisplayName("中文元素按 utf8mb4 原样往返：快照抄回来的与主表新值都不许变成问号或乱码")
    void cjkElementsSurviveTheWholeRoundTrip() throws Exception {
        String a = "[{\"type\":\"text\",\"x\":1,\"y\":0,\"text\":\"甲乙\",\"size\":24}]";
        String b = "[{\"type\":\"text\",\"x\":1,\"y\":0,\"text\":\"丙丁\",\"size\":24}]";
        insertTemplate(101, "FG_101", a);

        service.update(payload(101, b));

        assertThat(committedHistElements(101L, 1)).isEqualTo(a);
        assertThat(committedMainElements(101L)).isEqualTo(b);
        // 直接问库要 HEX：连接器/列集错了的话，等值断言可能两边一起烂掉而看不出来
        String hex = (String) queryCommitted("SELECT HEX(elements) AS h FROM base_label_template_hist"
                + " WHERE template_id=101 AND seq=1").get(0).get("h");
        assertThat(hex).contains("E794B2E4B999");   // 甲乙 的 UTF-8
    }

    // ────────────────────────── Task 7：历史摘要 / 详情 / 可再次回滚 ──────────────────────────

    /** 直接灌一条 hist（构造「按现行度量越界」「历史 bizType=CARTON」这类正常写序写不出的形态）。 */
    private void insertHist(long id, long templateId, int seq, String bizType, String elements) {
        jdbc.update("INSERT INTO base_label_template_hist (id, template_id, seq, biz_type, width_mm,"
                        + " height_mm, gap_mm, dpi, direction, copies, elements, create_by, create_time)"
                        + " VALUES (?,?,?,?,80,60,3,203,'NORMAL',1,?,42,NOW())",
                id, templateId, seq, bizType, elements);
    }

    private static SysUser user(long id, String realName) {
        SysUser u = new SysUser();
        u.setId(id);
        u.setRealName(realName);
        return u;
    }

    /** 只有一个静态中文 text 元素的一版（元素数恰为 1，字节数才能对着 UTF-8 手算）。 */
    private static String chineseElements(String word) {
        return "[{\"type\":\"text\",\"x\":1,\"y\":0,\"text\":\"" + word + "\",\"size\":24}]";
    }

    @Test
    @DisplayName("history 摘要：seq 倒序 2、1 对应 B、A；elementsBytes 是 UTF-8 字节数不是字符数；姓名批量注入")
    void historySummariesAreNewestFirstWithUtf8ByteCounts() throws Exception {
        String a = chineseElements("甲乙");
        String b = chineseElements("丙丁");
        String c = chineseElements("戊己");
        insertTemplate(101, "FG_101", a);
        service.update(payload(101L, b));
        service.update(payload(101L, c));
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(42L, "王师傅")));

        List<LabelHistorySummary> rows = service.history(101L);

        assertThat(rows).extracting(LabelHistorySummary::getSeq).containsExactly(2, 1);
        // A→B→C 写了 seq1=A、seq2=B；倒序首行是 2，内容必须对得上 B（用只读接口自己交叉验证）
        assertThat(service.historyOne(101L, 2).getElements()).isEqualTo(b);
        assertThat(service.historyOne(101L, 1).getElements()).isEqualTo(a);
        for (LabelHistorySummary r : rows) {
            assertThat(r.getCreateBy()).isEqualTo(42L);
            assertThat(r.getCreateByName()).isEqualTo("王师傅");
            assertThat(r.getCreateTime()).isNotNull();
            assertThat(r.getElementCount()).isEqualTo(1);
        }
        String seq2Content = b;
        assertThat(rows.get(0).getElementsBytes())
                .as("OCTET_LENGTH 给的是 UTF-8 字节数：中文 2 字 = 6 字节，字符串 length 是 70 上下")
                .isEqualTo(seq2Content.getBytes(StandardCharsets.UTF_8).length)
                .isNotEqualTo((long) seq2Content.length());
    }

    @Test
    @DisplayName("摘要 SQL 不回 elements 原文：summaryOf 只有投影列，20 版窗口与保留窗口同宽（LIMIT 20）")
    void summaryProjectionCarriesNoRawJson() throws Exception {
        insertTemplate(101, "FG_101", elementsAt(0));
        for (int i = 1; i <= 21; i++) {
            service.update(payload(101L, elementsAt(i * 2)));
        }
        List<LabelHistorySummary> rows = histMapper.summaryOf(101L);
        assertThat(rows).as("库里剩 20 版，摘要也恰好 20 行").hasSize(20);
        assertThat(rows).extracting(LabelHistorySummary::getSeq)
                .containsExactly(21, 20, 19, 18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2);
        // 投影列之外什么都没有：elements 根本不在 SELECT 里（DTO 无此字段，见 LabelSnapshotTest 的 JSON 钉子）
        assertThat(rows.get(0).getElementsBytes())
                .isEqualTo(elementsAt(40).getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    @DisplayName("坏 JSON 行：真库上 element_count 被 JSON_VALID 兜成 0，摘要整页照常返回（elements 是 LONGTEXT，脏行可能存在）")
    void summaryOfDirtyJsonRowDegradesToZeroElementCount() throws Exception {
        // 走 insertHist 直写而不是保存闸：闸会先把 "{not json" 拦成校验错误，永远落不进 hist。
        // 这行模拟的就是 sql/158 之后可能存在的脏数据（列是 LONGTEXT，没有 JSON 约束）。
        insertTemplate(101, "FG_101", elementsAt(0));
        service.update(payload(101L, elementsAt(10)));            // seq1 = 合法 JSON，1 个元素
        String dirty = "{not json";
        insertHist(777903L, 101L, 2, "FG_ROLL", dirty);           // seq2 = 坏 JSON

        List<LabelHistorySummary> rows = histMapper.summaryOf(101L);

        assertThat(rows).as("一列脏数据不许把整个历史抽屉打挂").hasSize(2);
        assertThat(rows).extracting(LabelHistorySummary::getSeq).containsExactly(2, 1);
        LabelHistorySummary dirtyRow = rows.get(0);
        assertThat(dirtyRow.getElementCount())
                .as("JSON_LENGTH 在坏 JSON 上是 MySQL 报错，必须先被 JSON_VALID 兜成 0")
                .isZero();
        // OCTET_LENGTH 不看 JSON 合不合法：坏行的字节数照样给得出来（UTF-8，9 个字符 = 9 字节）
        assertThat(dirtyRow.getElementsBytes())
                .isEqualTo(dirty.getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(0L);
        assertThat(rows.get(1).getElementCount())
                .as("同一页里的合法行不受坏行牵连，守卫是逐行的")
                .isEqualTo(1);
        // 上面全部是 mapper 层的证据，而 DisplayName 承诺的是「整页照常返回」——那就把服务这一层也走一遍：
        // requirePerm → detail(101) → summaryOf → 姓名注入，坏 JSON 行夹在里面任何一环崩掉都会红。
        assertThat(service.history(101L)).as("含坏行的历史抽屉照样打得开").hasSize(2);
    }

    @Test
    @DisplayName("回滚 seq1 后主表=A 且新增 seq3=C；再回滚 seq3 主表=C 且 seq4=A —— 回滚可以再回滚")
    void rollbackChainIsReRollable() throws Exception {
        String a = elementsAt(0);
        String b = elementsAt(10);
        String c = elementsAt(20);
        insertTemplate(101, "FG_101", a);
        service.update(payload(101L, b));
        service.update(payload(101L, c));
        // 名称/备注/默认位/状态改成与任何历史都无关的值，回滚后必须原样还在
        jdbc.update("UPDATE base_label_template SET template_name='改名后', remark='新备注',"
                + " is_default=1, status=0 WHERE id=101");

        service.rollback(101L, 1);
        assertThat(committedMainElements(101L)).as("seq1 存的是被第一次覆盖掉的 A").isEqualTo(a);
        assertThat(committedSeqs(101L)).containsExactly(1, 2, 3);
        assertThat(committedHistElements(101L, 3)).as("回滚自己留痕：seq3 = 覆盖前的 C").isEqualTo(c);

        service.rollback(101L, 3);
        assertThat(committedMainElements(101L)).as("seq3 存的是 C，回到原地").isEqualTo(c);
        assertThat(committedSeqs(101L)).containsExactly(1, 2, 3, 4);
        assertThat(committedHistElements(101L, 4)).isEqualTo(a);

        Map<String, Object> main = queryCommitted(
                "SELECT template_name, template_code, is_default, status, remark"
                        + " FROM base_label_template WHERE id=101").get(0);
        assertThat(main.get("template_name")).isEqualTo("改名后");
        assertThat(main.get("template_code")).isEqualTo("FG_101");
        assertThat(main.get("is_default")).isEqualTo(1);
        assertThat(main.get("status")).isEqualTo(0);
        assertThat(main.get("remark")).isEqualTo("新备注");
    }

    @Test
    @DisplayName("别的模板的 seq 读不到也恢复不了；模板不存在/seq 非法分别 404/400，且什么都不写")
    void rollbackAndHistoryOneAreTemplateScoped() throws Exception {
        String a = elementsAt(0);
        insertTemplate(101, "FG_101", a);
        insertTemplate(102, "FG_102", a);
        service.update(payload(101L, elementsAt(10)));          // 101: seq1
        service.update(payload(102L, elementsAt(10)));          // 102: seq1
        service.update(payload(102L, elementsAt(20)));          // 102: seq2（101 没有 seq2）

        assertThatThrownBy(() -> service.historyOne(101L, 2)).isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.rollback(101L, 2)).isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
        assertThat(committedMainElements(101L)).isEqualTo(elementsAt(10));
        assertThat(committedSeqs(102L)).as("102 的 seq2 完好无损").containsExactly(1, 2);
        assertThat(committedSeqs(101L)).containsExactly(1);

        assertThatThrownBy(() -> service.rollback(999L, 1)).isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.rollback(101L, 0)).isInstanceOf(BizException.class)
                .hasMessageContaining("seq");
        assertThatThrownBy(() -> service.historyOne(101L, -3)).isInstanceOf(BizException.class)
                .hasMessageContaining("seq");
        assertThat(committedHistCount(101L)).isEqualTo(1);
    }

    @Test
    @DisplayName("trigger 让回滚的主表 UPDATE 失败：本次新快照一起回滚，主表停在 C")
    void failedRollbackRollsBackItsOwnSnapshot() throws Exception {
        String c = elementsAt(20);
        insertTemplate(101, "FG_101", elementsAt(0));
        service.update(payload(101L, elementsAt(10)));
        service.update(payload(101L, c));
        installFailingTrigger();
        Throwable thrown;
        try {
            thrown = catchThrowable(() -> service.rollback(101L, 1));
        } finally {
            dropFailingTriggerQuietly();
        }

        assertThat(thrown).as("回滚必须整体失败").isNotNull();
        assertAnyCauseHas(thrown, FORCED);
        assertThat(committedMainElements(101L)).as("主表不许停在 A").isEqualTo(c);
        assertThat(committedSeqs(101L)).as("seq3（回滚自己的快照）必须跟着回滚").containsExactly(1, 2);
    }

    @Test
    @DisplayName("历史版按现行度量越界：回滚过闸失败 → 主表与 hist 都不动（真库版）")
    void rollbackToOutOfBoundsHistoryWritesNothingOnRealMysql() throws Exception {
        String a = elementsAt(0);
        insertTemplate(101, "FG_101", a);
        insertHist(777901L, 101L, 7, "FG_ROLL", chineseElements("甲乙")
                .replace("\"y\":0", "\"y\":9000"));           // 480 点的画布上 y=9000 必然越界

        assertThatThrownBy(() -> service.rollback(101L, 7)).isInstanceOf(BizException.class)
                .hasMessageContaining("越界");
        assertThat(committedMainElements(101L)).isEqualTo(a);
        assertThat(committedSeqs(101L)).as("越界的旧版还在原处（没被裁也没被抄），但没写任何新版")
                .containsExactly(7);
    }

    @Test
    @DisplayName("rollback 恢复历史里合法的 bizType=CARTON（update 禁止改类型，回滚走的是恢复列）")
    void rollbackRestoresHistoricalBizTypeOnRealMysql() throws Exception {
        insertTemplate(101, "FG_101", elementsAt(0));           // 主表 FG_ROLL
        String carton = "[{\"type\":\"text\",\"x\":10,\"y\":10,\"field\":\"customerName\",\"size\":24}]";
        insertHist(777902L, 101L, 8, "CARTON", carton);

        service.rollback(101L, 8);

        Map<String, Object> main = queryCommitted(
                "SELECT biz_type, elements FROM base_label_template WHERE id=101").get(0);
        assertThat(main.get("biz_type")).isEqualTo("CARTON");
        assertThat(main.get("elements")).isEqualTo(carton);
        assertThat(committedSeqs(101L)).as("覆盖前的 FG_ROLL 那版照常留史").containsExactly(8, 9);
    }

    @Test
    @DisplayName("回滚到最旧一版：该 hist 行被本次裁剪挤出去也不影响已读副本，主表拿到 E(2)")
    void rollbackOfTheOldestRetainedVersionSurvivesItsOwnPruning() throws Exception {
        insertTemplate(101, "FG_101", elementsAt(0));
        for (int i = 1; i <= 21; i++) {
            service.update(payload(101L, elementsAt(i * 2)));
        }
        // 库里此刻是 seq2..21，最旧可回滚 = seq2（内容 E(2)），主表 E(42)
        service.rollback(101L, 2);

        assertThat(committedMainElements(101L))
                .as("目标行内容必须在裁剪前读到内存：seq2 被本次新快照挤出去也要回得来")
                .isEqualTo(elementsAt(2));
        assertThat(committedSeqs(101L)).as("新快照 seq22 落库后 seq2 被裁掉，剩 3..22")
                .containsExactly(3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22);
        assertThat(committedHistElements(101L, 22)).isEqualTo(elementsAt(42));
    }

    // ────────────────────────── 当前读 / 快照读的小探针 ──────────────────────────

    /** 普通（快照）读：和 {@code BaseLabelTemplateHistMapper#maxSeq} 只差一个 FOR UPDATE。 */
    private Integer plainMaxSeq(Connection c, long templateId) throws SQLException {
        return maxSeqFrom(c, "SELECT seq FROM base_label_template_hist WHERE template_id=? "
                + "ORDER BY seq DESC LIMIT 1", templateId);
    }

    /** 锁定（当前）读：服务里真正用的那一条 SQL。 */
    private Integer lockingMaxSeq(Connection c, long templateId) throws SQLException {
        return maxSeqFrom(c, "SELECT seq FROM base_label_template_hist WHERE template_id=? "
                + "ORDER BY seq DESC LIMIT 1 FOR UPDATE", templateId);
    }

    private Integer maxSeqFrom(Connection c, String sql, long templateId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, templateId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    /**
     * 当前会话的事务级别。只在 autocommit 还开着的时候用 —— 它是条普通 SELECT，
     * 放进 RR 事务里会顺手把 read view 提前定下来，把快照读那半个用例搅脏。
     */
    private static String sessionIsolation(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT @@transaction_isolation")) {
            rs.next();
            return rs.getString(1);
        }
    }
}
