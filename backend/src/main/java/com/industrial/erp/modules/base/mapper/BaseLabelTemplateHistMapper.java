package com.industrial.erp.modules.base.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.base.entity.BaseLabelTemplateHist;
import com.industrial.erp.modules.base.label.LabelHistorySummary;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * v1.1.168 标签模板历史快照 (docs/36 Task 6)。
 *
 * <p>这三条 SQL 的共同点：**都带 {@code template_id}，都是锁定当前读**（{@code FOR UPDATE}），
 * 不是普通快照读。当前读带来两件事：一、拿到的是别的编辑**刚刚提交**的最大序号，而不是本会话
 * 第一次普通 SELECT 时定下的 read view（机制证据：{@code LabelSnapshotIT
 * .repeatableReadSnapshotLagsButLockingReadDoesNot} —— 同一条 REPEATABLE READ 会话里普通读
 * 看不到刚提交的 seq1，加了 FOR UPDATE 就看得到）；二、这一段区间在锁里，第二个编辑必须等
 * 前一个提交后才能算 seq，撞不出两个相同 seq。
 *
 * <p>调用方 {@code BaseLabelTemplateService.update} 跑在 READ COMMITTED（同模板串行化靠的是主表
 * {@code selectForUpdate} 那一行锁，不是这里）：RC 不给不命中的区间留 gap 锁，所以两个不同模板
 * 同时首次写快照不会互相卡成 1213。把事务级别改回 REPEATABLE READ 的话，
 * {@code LabelSnapshotIT.differentTemplatesNumberIndependently} 会先红 —— 那是实测过的，不是推演。
 * 而 {@code FOR UPDATE} 本身仍是「读到最新已提交序号」这件事的显式声明，去掉它等于把序号
 * 的正确性完全押在调用方的隔离级别上。
 *
 * <p>**评审结论（Task 6 复核 I-2，留档用）**：在现在这套调用配置下 —— 外层已经是 READ COMMITTED，
 * 而且 {@code BaseLabelTemplateService.update} 先锁住了主表这一模板的行 —— 这三条 SQL 的锁定读
 * 是**冗余的保险**，不是唯一的正确性来源。冗余是故意的：这三条方法是对外的 Mapper API，
 * 一旦哪天有人从别的路径（不带主表行锁 / 换了隔离级别）调它们，快照读就会读出重复 seq。
 * 所以**不要把它们「简化」成普通 SELECT**，也不要在没有并发用例变红的情况下改这两句话。
 *
 * <p>Task 7 起的三条读方法不在此列，且是**故意**分开的：{@code summaryOf} 与 {@code selectVersion}
 * 是只读路径（列表投影 / 单版全量），走普通快照读就行，不加 {@code FOR UPDATE}；
 * 只有 {@code selectVersionForUpdate} 服务回滚写路径，和上面三条一样是锁定当前读。
 */
@Mapper
public interface BaseLabelTemplateHistMapper extends BaseMapper<BaseLabelTemplateHist> {

    /**
     * 该模板当前的最大序号（倒序首行）。没有历史时返回 {@code null}，调用方按 0 处理。
     *
     * <p>名字里的 max 是「当前最大值」的语义，实现上取的是排序首行 —— 因为 {@code MAX()}
     * 是聚合，走不了 {@code FOR UPDATE}；{@code ORDER BY seq DESC LIMIT 1 FOR UPDATE} 既给同一个数，
     * 又顺带把这段区间锁住，让第二个编辑必须等第一个提交后才能算 seq。
     */
    @Select("SELECT seq FROM base_label_template_hist WHERE template_id = #{templateId} "
            + "ORDER BY seq DESC LIMIT 1 FOR UPDATE")
    Integer maxSeq(@Param("templateId") Long templateId);

    /**
     * 需要保留的最近 20 版 id（同样是锁定当前读）。裁剪把这批之外的行删掉。
     *
     * <p>用倒序首 20 行而不是 {@code MAX}/{@code NOT IN (SELECT ...)}：LIMIT 之后锁的就是这批记录
     * 本身（READ COMMITTED 下不扩散到空档），而且刚插进去的那一版按定义一定在这 20 行里，
     * 不需要再判断「新行会不会被自己裁掉」。
     */
    @Select("SELECT id FROM base_label_template_hist WHERE template_id = #{templateId} "
            + "ORDER BY seq DESC LIMIT 20 FOR UPDATE")
    List<Long> retainedIds(@Param("templateId") Long templateId);

    /**
     * 删掉该模板保留集合之外的历史。id 全部走 foreach 绑定参数，绝不拼字符串；
     * {@code template_id} 恒在，防止一个模板的保留集合裁到另一个模板头上。
     *
     * <p>空列表**不许调这个方法**（{@code NOT IN ()} 是语法错误）—— 由调用方先判空。
     *
     * <p>{@code FORCE INDEX (idx_template_seq)} 是把**锁定范围钉死在本模板自己的索引区间**上：
     * 一旦优化器改走别的计划（主键扫描），这条 DELETE 就要摸到别的模板刚插入、尚未提交的那些行，
     * 锁范围于是超出 {@link #retainedIds} 已经握着的那一批。注意这是预防，不是实测过的故障 ——
     * 9000 行、{@code ANALYZE TABLE} 之后的本机 MySQL 8.0.46 上，不加提示也照样选中
     * {@code idx_template_seq}（EXPLAIN {@code type=range}，加了提示是 {@code type=ref}），
     * 两个模板并发裁剪都不报 1213。<b>这里没有留下「不加提示就会死锁」的证据，别照着这句话去改回去。</b>
     *
     * <p>写成 {@code DELETE hist FROM ... AS hist} 这种多表形式**只是为了能带索引提示**：
     * MySQL 8 的单表 DELETE 语法不接受 index hint（本机 8.0.46 实测 {@code ERROR 1064}），而这里只列了一张表，
     * 删除语义与单表形式完全一致。
     *
     * <p>**2026-11-07 生产回滚修复（docs/36 Task 14 B02）**：这条 SQL 之所以必须走多表形态 +
     * {@code FORCE INDEX}，是它同时满足两个约束：①防死锁（锁范围 = {@link #retainedIds} 已握的
     * 那一小段 {@code idx_template_seq}，两个模板并发裁剪互不侵入）；②SQL 在 MySQL 8 上可执行。
     * 而生产 {@code MybatisPlusConfig} 里挂的 {@code BlockAttackInnerInterceptor} 走 jsqlparser
     * 解析 SQL，**连 {@code FORCE} 这个关键字都 parse 不了**（本机实测 {@code ParseException:
     * Encountered unexpected token: "FORCE"}）⇒ 原写法一发就要抛
     * {@code MybatisPlusException: Failed to process}。
     *
     * <p>正解 = **保留 SQL 原样，按 statementId 白名单关掉这条 SQL 的 BlockAttack**：
     * {@link InterceptorIgnore}（MyBatis-Plus 3.5.9 原生机制，字节码见
     * {@code InterceptorIgnoreHelper.willIgnoreBlockAttack}）按 mapper 方法名把
     * {@code deleteExcept} 从防全表删除的拦截里豁免掉。豁免范围**只限定在这一条 statement**
     * —— 其它所有 update/delete（同文件其它方法、同表其它写路径）仍照常走 BlockAttack 全量拦截。
     * 之所以豁免而不是改写 SQL：{@code FORCE INDEX} 是并发串行化（{@code differentTemplatesNumberIndependently}
     * 用例）的承重件，去掉就变回 4/8 死锁；改写为 {@code SELECT … FOR UPDATE} + 逐行 delete 又要把
     * 事务里多塞一次全量快照读，代价更大。
     */
    @InterceptorIgnore(blockAttack = "true")
    @Delete("<script>DELETE hist FROM base_label_template_hist AS hist FORCE INDEX (idx_template_seq) "
            + "WHERE hist.template_id = #{templateId} AND hist.id NOT IN "
            + "<foreach collection='ids' item='retainedId' open='(' separator=',' close=')'>"
            + "#{retainedId}</foreach></script>")
    int deleteExcept(@Param("templateId") Long templateId, @Param("ids") List<Long> ids);

    /**
     * Task 7 Step 2：历史列表的投影查询（seq/操作者/时刻 + 字节数/元素数），**不带 elements 原文**。
     * 倒序、限 20 —— 与保留窗口同宽，摘要永远能覆盖到可回滚的那批版本。
     *
     * <p>{@code OCTET_LENGTH} 给 UTF-8 字节数（不是 Java String.length）；
     * {@code JSON_LENGTH} 在坏 JSON 上会直接报错，所以先 {@code JSON_VALID} 兜 0 ——
     * 摘要不许因为一列脏数据把整个历史抽屉打挂。
     */
    @Select("SELECT seq, create_by, create_time, "
            + "OCTET_LENGTH(elements) AS elements_bytes, "
            + "CASE WHEN JSON_VALID(elements) THEN JSON_LENGTH(elements) ELSE 0 END AS element_count "
            + "FROM base_label_template_hist WHERE template_id = #{templateId} "
            + "ORDER BY seq DESC LIMIT 20")
    List<LabelHistorySummary> summaryOf(@Param("templateId") Long templateId);

    /** Task 7 Step 3：historyOne 的只读查询（普通快照读，**不带 FOR UPDATE**，别顺手统一）。 */
    @Select("SELECT * FROM base_label_template_hist WHERE template_id = #{templateId} AND seq = #{seq}")
    BaseLabelTemplateHist selectVersion(@Param("templateId") Long templateId, @Param("seq") int seq);

    /** Task 7 Step 3：rollback 用锁定当前读取目标版；template_id 恒在，别的模板的 seq 读不到。 */
    @Select("SELECT * FROM base_label_template_hist WHERE template_id = #{templateId} AND seq = #{seq} FOR UPDATE")
    BaseLabelTemplateHist selectVersionForUpdate(@Param("templateId") Long templateId, @Param("seq") int seq);
}
