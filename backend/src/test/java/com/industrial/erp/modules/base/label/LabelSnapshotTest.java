package com.industrial.erp.modules.base.label;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import com.industrial.erp.modules.base.entity.BaseLabelTemplateHist;
import com.industrial.erp.modules.base.mapper.BaseLabelTemplateHistMapper;
import com.industrial.erp.modules.base.mapper.BaseLabelTemplateMapper;
import com.industrial.erp.modules.base.service.BaseLabelTemplateService;
import com.industrial.erp.modules.system.entity.SysUser;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import com.industrial.erp.security.PermissionService;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 快照写路径的**单元**钉子 (docs/36 Task 6 Step 1)。
 *
 * <p>这里只钉**分支形状**：快照取的是覆盖前的旧行、序号是 max+1、闸没过就不留史、
 * 影响行数不是 1 绝不假装保存成功。真正的「一条 UPDATE 失败会不会留下孤儿历史」是 MySQL
 * 事务语义问题，Mockito 桩模拟不出来，由 {@code LabelSnapshotIT} 在真库上验（brief Step 1 末行）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LabelSnapshotTest {

    @Mock private BaseLabelTemplateMapper mapper;
    @Mock private BaseLabelTemplateHistMapper histMapper;
    @Mock private PermissionService permService;
    @Mock private SysUserMapper userMapper;

    /**
     * 与 BaseLabelTemplateServiceTest 同一理由：LambdaQueryWrapper/LambdaUpdateWrapper 会把 lambda
     * 立刻解析成列名，纯 Mockito 环境里没有 SqlSessionFactory，TableInfo 缓存是空的。
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "label-snapshot-test"),
                BaseLabelTemplate.class);
    }

    @BeforeEach
    void grant() {
        when(permService.hasPerm(BaseLabelTemplateService.PERM)).thenReturn(true);
        when(permService.getCurrentUserId()).thenReturn(42L);
        when(mapper.selectCount(any())).thenReturn(0L);
    }

    /** 覆盖前库里那一版（A）：纸头与元素都刻意与 B 不同，才能证明快照抄的是 old 而不是 next。 */
    private BaseLabelTemplate a() {
        BaseLabelTemplate t = new BaseLabelTemplate();
        t.setId(9001L);
        t.setTemplateCode("FG_TEST");
        t.setTemplateName("测试卷标");
        t.setBizType("FG_ROLL");
        t.setWidthMm(80); t.setHeightMm(60); t.setGapMm(3); t.setDpi(203);
        t.setDirection("NORMAL"); t.setCopies(1);
        t.setIsDefault(0); t.setStatus(1); t.setRemark("旧备注");
        t.setElements("[{\"type\":\"text\",\"x\":1,\"y\":0,\"field\":\"batchNo\",\"size\":24}]");
        t.setCreateTime(LocalDateTime.of(2020, 1, 1, 0, 0));
        t.setCreateBy(7L);
        return t;
    }

    /** 客户端 PUT 上来的新一版（B）：每个纸头字段都变了。 */
    private BaseLabelTemplate b() {
        BaseLabelTemplate t = new BaseLabelTemplate();
        t.setId(9001L);
        t.setTemplateCode("FG_TEST");
        t.setTemplateName("测试卷标改名");
        t.setBizType("FG_ROLL");
        t.setWidthMm(100); t.setHeightMm(50); t.setGapMm(5); t.setDpi(300);
        t.setDirection("REVERSE"); t.setCopies(2);
        t.setRemark("新备注");
        t.setElements("[{\"type\":\"text\",\"x\":10,\"y\":10,\"field\":\"batchNo\",\"size\":24}]");
        return t;
    }

    private BaseLabelTemplateService svc() {
        return new BaseLabelTemplateService(mapper, new LabelTemplateRenderer(), permService, histMapper, userMapper);
    }

    /**
     * 把「库里那一版」同时桩到两种读法上。
     *
     * <p>不这么做的话，实现改用 {@code selectForUpdate} 之前这些用例会全部撞在
     * 「标签模板不存在或已删除」上，红得没有信息量 —— 看不出到底是没写历史、还是没检查影响行数。
     * 桩住两条路，每条断言的失败原因就只剩它自己盯的那件事。
     */
    private void stored(BaseLabelTemplate old) {
        when(mapper.selectById(old.getId())).thenReturn(old);
        when(mapper.selectForUpdate(old.getId())).thenReturn(old);
    }

    private BaseLabelTemplateHist capturedHist() {
        ArgumentCaptor<BaseLabelTemplateHist> cap = ArgumentCaptor.forClass(BaseLabelTemplateHist.class);
        verify(histMapper).insert(cap.capture());
        return cap.getValue();
    }

    // ────────────────────────── 断言 1：快照 = 覆盖前的 A ──────────────────────────

    @Test
    @DisplayName("update 把 A 改成 B：hist 抄的是 A 的纸头与元素，templateId 对得上，首版 seq=1")
    void snapshotIsTheOldRowNotTheIncomingOne() {
        BaseLabelTemplate old = a();
        stored(old);
        when(histMapper.maxSeq(9001L)).thenReturn(null);   // 该模板还没有任何历史
        when(mapper.updateById(any(BaseLabelTemplate.class))).thenReturn(1);

        svc().update(b());

        BaseLabelTemplateHist hist = capturedHist();
        assertThat(hist.getTemplateId()).isEqualTo(9001L);
        assertThat(hist.getSeq()).isEqualTo(1);
        assertThat(hist.getBizType()).isEqualTo("FG_ROLL");
        assertThat(hist.getWidthMm()).isEqualTo(80);
        assertThat(hist.getHeightMm()).isEqualTo(60);
        assertThat(hist.getGapMm()).isEqualTo(3);
        assertThat(hist.getDpi()).isEqualTo(203);
        assertThat(hist.getDirection()).isEqualTo("NORMAL");
        assertThat(hist.getCopies()).isEqualTo(1);
        assertThat(hist.getElements()).isEqualTo(old.getElements());
        // 反过来钉：抄成 next 的话这些值会变成 100/50/5/300/REVERSE/2 与 B 的元素
        assertThat(hist.getElements()).isNotEqualTo(b().getElements());
    }

    @Test
    @DisplayName("seq 取锁内当前最大值 +1，不是 1 也不是固定 20")
    void seqIsMaxPlusOne() {
        stored(a());
        when(histMapper.maxSeq(9001L)).thenReturn(19);
        when(mapper.updateById(any(BaseLabelTemplate.class))).thenReturn(1);

        svc().update(b());

        assertThat(capturedHist().getSeq()).isEqualTo(20);
    }

    @Test
    @DisplayName("快照操作者取 permService.getCurrentUserId、时间是本次更新时刻（不抄旧行的 createBy/createTime）")
    void operatorAndTimeComeFromThisUpdateNotTheOldRow() {
        BaseLabelTemplate old = a();
        stored(old);
        when(histMapper.maxSeq(9001L)).thenReturn(null);
        when(mapper.updateById(any(BaseLabelTemplate.class))).thenReturn(1);

        LocalDateTime before = LocalDateTime.now();
        svc().update(b());

        BaseLabelTemplateHist hist = capturedHist();
        assertThat(hist.getCreateBy()).isEqualTo(42L);
        assertThat(hist.getCreateTime()).isNotNull().isAfterOrEqualTo(before);
        // 旧行是 2020-01-01 / 用户 7；抄过来就说明快照把「这版是谁什么时候存的」记成了「模板是谁建的」
        assertThat(hist.getCreateTime()).isNotEqualTo(old.getCreateTime());
    }

    @Test
    @DisplayName("裁到 20：插入后按 retainedIds 删其余，且始终带 template_id")
    void prunesToTheRetainedTwentyWithTemplateScopedDelete() {
        stored(a());
        when(histMapper.maxSeq(9001L)).thenReturn(20);
        when(mapper.updateById(any(BaseLabelTemplate.class))).thenReturn(1);
        List<Long> retained = new ArrayList<>();
        for (long i = 0; i < 20; i++) retained.add(500L + i);
        when(histMapper.retainedIds(9001L)).thenReturn(retained);

        svc().update(b());

        verify(histMapper).deleteExcept(9001L, retained);
    }

    @Test
    @DisplayName("retainedIds 为空时不发 NOT IN 删除（空列表拼出来就是 SQL 语法错误）")
    void emptyRetainedListSkipsTheDelete() {
        stored(a());
        when(histMapper.maxSeq(9001L)).thenReturn(null);
        when(mapper.updateById(any(BaseLabelTemplate.class))).thenReturn(1);

        svc().update(b());

        verify(histMapper, never()).deleteExcept(any(), anyList());
    }

    // ────────────────────────── 断言 2：历史字段只有纸头 + 元素 ──────────────────────────

    @Test
    @DisplayName("hist 实体没有 name/code/status/isDefault/remark，也没有 deleted/tenant_id")
    void histCarriesNoBusinessOrAuditExtras() {
        Set<String> fields = Arrays.stream(BaseLabelTemplateHist.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic() && !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertThat(fields).containsExactlyInAnyOrder(
                "id", "templateId", "seq", "bizType",
                "widthMm", "heightMm", "gapMm", "dpi", "direction", "copies",
                "elements", "createBy", "createTime");
        assertThat(fields).doesNotContain("templateName", "templateCode", "status", "isDefault",
                "remark", "deleted", "updateBy", "updateTime", "tenantId");
    }

    // ────────────────────────── 断言 3：add / delete 不写历史 ──────────────────────────

    @Test
    @DisplayName("add 只 INSERT 主表，一条历史都不写（新建还没有「被覆盖的旧内容」）")
    void addWritesNoHistory() {
        BaseLabelTemplate fresh = b();
        fresh.setId(null);

        svc().add(fresh);

        verify(mapper).insert(fresh);
        verify(histMapper, never()).insert(any(BaseLabelTemplateHist.class));
        verify(mapper, never()).selectForUpdate(any());
    }

    @Test
    @DisplayName("delete 只软删主表，不写历史（历史是「覆盖前的旧内容」，不是「删过谁」）")
    void deleteWritesNoHistory() {
        when(mapper.selectById(9001L)).thenReturn(a());

        svc().delete(9001L);

        verify(mapper).update(any(), any());
        verify(histMapper, never()).insert(any(BaseLabelTemplateHist.class));
    }

    // ────────────────────────── 断言 4：影响行数不是 1 就是失败 ──────────────────────────

    @Test
    @DisplayName("主表 updateById 返回 0 → 抛异常，绝不静默报「保存成功」")
    void zeroAffectedRowsIsAnError() {
        stored(a());
        when(histMapper.maxSeq(9001L)).thenReturn(null);
        when(mapper.updateById(any(BaseLabelTemplate.class))).thenReturn(0);

        assertThatThrownBy(() -> svc().update(b())).isInstanceOf(BizException.class)
                .hasMessageContaining("9001");
    }

    @Test
    @DisplayName("主表 updateById 返回 2（多于预期的一行）同样算失败")
    void moreThanOneAffectedRowIsAlsoAnError() {
        stored(a());
        when(histMapper.maxSeq(9001L)).thenReturn(null);
        when(mapper.updateById(any(BaseLabelTemplate.class))).thenReturn(2);

        assertThatThrownBy(() -> svc().update(b())).isInstanceOf(BizException.class);
    }

    // ────────────────────────── 串行与闸的额外钉子 ──────────────────────────

    @Test
    @DisplayName("update 走 selectForUpdate 行锁，不再用普通 selectById 读旧行")
    void updateTakesTheRowLock() {
        stored(a());
        when(histMapper.maxSeq(9001L)).thenReturn(null);
        when(mapper.updateById(any(BaseLabelTemplate.class))).thenReturn(1);

        svc().update(b());

        verify(mapper).selectForUpdate(9001L);
        verify(mapper, never()).selectById(any());
    }

    @Test
    @DisplayName("行不存在 → 404，且一行历史都不写")
    void missingRowWritesNoHistory() {
        when(mapper.selectForUpdate(9001L)).thenReturn(null);

        assertThatThrownBy(() -> svc().update(b())).isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
        verify(histMapper, never()).insert(any(BaseLabelTemplateHist.class));
    }

    @Test
    @DisplayName("没过保存闸就不产生快照（gate 必须排在 snapshotAndReplace 之前）")
    void gateRejectionWritesNoHistory() {
        stored(a());
        BaseLabelTemplate bad = b();
        bad.setElements("[{\"type\":\"text\",\"x\":1,\"y\":0,\"field\":\"nonsense\",\"size\":24}]");

        assertThatThrownBy(() -> svc().update(bad)).isInstanceOf(BizException.class)
                .hasMessageContaining("nonsense");
        verify(histMapper, never()).insert(any(BaseLabelTemplateHist.class));
        verify(mapper, never()).updateById(any(BaseLabelTemplate.class));
    }

    @Test
    @DisplayName("缺 id 仍是 400，且不会去抢行锁")
    void missingIdIsClientErrorBeforeLocking() {
        BaseLabelTemplate noId = b();
        noId.setId(null);

        assertThatThrownBy(() -> svc().update(noId)).isInstanceOf(BizException.class)
                .hasMessageContaining("缺少模板 id");
        verify(mapper, never()).selectForUpdate(any());
        verify(histMapper, never()).insert(any(BaseLabelTemplateHist.class));
    }

    @Test
    @DisplayName("403 权限拦截发生在加锁与写史之前")
    void permissionIsCheckedFirst() {
        when(permService.hasPerm(BaseLabelTemplateService.PERM)).thenReturn(false);
        org.mockito.Mockito.doThrow(BizException.of(403, "无权限: " + BaseLabelTemplateService.PERM))
                .when(permService).requirePerm(BaseLabelTemplateService.PERM);

        assertThatThrownBy(() -> svc().update(b())).isInstanceOf(BizException.class)
                .hasMessageContaining("无权限");
        verify(mapper, never()).selectForUpdate(any());
        verify(histMapper, never()).insert(any(BaseLabelTemplateHist.class));
    }

    @Test
    @DisplayName("bizType 与 isDefault 仍按库里那一行钉死（A8 裁定 R2/R6 在锁读之后照旧生效）")
    void identityStillPinnedFromStoredRow() {
        BaseLabelTemplate row = a();
        row.setIsDefault(1);
        stored(row);
        when(histMapper.maxSeq(9001L)).thenReturn(null);
        when(mapper.updateById(any(BaseLabelTemplate.class))).thenReturn(1);

        BaseLabelTemplate payload = b();
        payload.setBizType("CARTON");
        payload.setElements("[{\"type\":\"text\",\"x\":10,\"y\":10,\"field\":\"customerName\",\"size\":24}]");

        // customerName 只在 CARTON 字典里合法；按 FG_ROLL 校必须拦，且快照抄的仍是 FG_ROLL
        assertThatThrownBy(() -> svc().update(payload)).isInstanceOf(BizException.class)
                .hasMessageContaining("customerName");
        verify(histMapper, never()).insert(any(BaseLabelTemplateHist.class));
    }

    @Test
    @DisplayName("事务边界在公共入口 update 上（rollbackFor=Exception + READ_COMMITTED），snapshotAndReplace 是私有方法")
    void transactionBoundaryIsOnThePublicEntry() throws Exception {
        // 自调用不会经过 Spring 代理，把 @Transactional 标在私有 snapshotAndReplace 上等于没有事务；
        // 而 update 少了 rollbackFor，BizException 之外的受检异常也不会回滚。这两条只能靠形状钉住。
        java.lang.reflect.Method update = BaseLabelTemplateService.class
                .getMethod("update", BaseLabelTemplate.class);
        org.springframework.transaction.annotation.Transactional tx =
                update.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(tx).as("update 必须自己带 @Transactional，否则行锁随语句自动提交就掉了").isNotNull();
        assertThat(tx.rollbackFor()).as("rollbackFor 必须含 Exception，BizException 之外的异常也要回滚")
                .contains(Exception.class);
        // 默认 REPEATABLE READ 下，空历史区间的 FOR UPDATE 会留 gap 锁，不同模板首次写快照时
        // 两边互相把 insert-intention 锁卡在对方的 gap 锁上（实测 1213 Deadlock found）。
        // READ COMMITTED 关掉 gap 锁，同模板串行化仍由主表行锁保证。
        assertThat(tx.isolation()).as("update 必须显式 READ_COMMITTED，否则跨模板 gap 锁互锁死锁")
                .isEqualTo(org.springframework.transaction.annotation.Isolation.READ_COMMITTED);

        java.lang.reflect.Method snapshot = BaseLabelTemplateService.class
                .getDeclaredMethod("snapshotAndReplace", BaseLabelTemplate.class, BaseLabelTemplate.class);
        assertThat(java.lang.reflect.Modifier.isPrivate(snapshot.getModifiers()))
                .as("snapshotAndReplace 只能是私有：公共方法会开出绕过行锁的第二条写路径").isTrue();
    }

    @Test
    @DisplayName("retainedIds 只在插入之后才查（裁剪顺序：快照 → update → 裁）")
    void pruningRunsAfterTheMainRowIsWritten() {
        stored(a());
        when(histMapper.maxSeq(9001L)).thenReturn(20);
        when(mapper.updateById(any(BaseLabelTemplate.class))).thenReturn(1);
        List<Long> retained = new ArrayList<>();
        for (long i = 0; i < 20; i++) retained.add(500L + i);
        when(histMapper.retainedIds(9001L)).thenReturn(retained);

        org.mockito.InOrder ordered = org.mockito.Mockito.inOrder(histMapper, mapper);
        svc().update(b());
        ordered.verify(histMapper).insert(any(BaseLabelTemplateHist.class));
        ordered.verify(mapper).updateById(any(BaseLabelTemplate.class));
        ordered.verify(histMapper).retainedIds(9001L);
        ordered.verify(histMapper).deleteExcept(eq(9001L), anyList());
    }

    // ══════════════════════════ Task 7：历史摘要 / 详情 / 可再次回滚 ══════════════════════════

    /** hist 表里的一行（= 某次覆盖之前的旧内容）。用主表行来造，纸头与元素天然成套。 */
    private BaseLabelTemplateHist histOf(int seq, BaseLabelTemplate old) {
        BaseLabelTemplateHist h = new BaseLabelTemplateHist();
        h.setId(555000L + seq);
        h.setTemplateId(old.getId());
        h.setSeq(seq);
        h.setBizType(old.getBizType());
        h.setWidthMm(old.getWidthMm());
        h.setHeightMm(old.getHeightMm());
        h.setGapMm(old.getGapMm());
        h.setDpi(old.getDpi());
        h.setDirection(old.getDirection());
        h.setCopies(old.getCopies());
        h.setElements(old.getElements());
        h.setCreateBy(42L);
        h.setCreateTime(LocalDateTime.of(2024, 1, 1, 0, 0));
        return h;
    }

    /** 当前主表停在 C：纸头与元素都和 A、B 不同，回滚后「哪些列回来了、哪些列没动」才看得出来。 */
    private BaseLabelTemplate c() {
        BaseLabelTemplate t = new BaseLabelTemplate();
        t.setId(9001L);
        t.setTemplateCode("FG_TEST");
        t.setTemplateName("现名");
        t.setBizType("FG_ROLL");
        t.setWidthMm(70); t.setHeightMm(70); t.setGapMm(4); t.setDpi(300);
        t.setDirection("REVERSE"); t.setCopies(3);
        t.setIsDefault(1); t.setStatus(0); t.setRemark("现备注");
        t.setElements("[{\"type\":\"text\",\"x\":30,\"y\":30,\"field\":\"batchNo\",\"size\":24}]");
        t.setCreateBy(7L);
        return t;
    }

    @Test
    @DisplayName("history 第一步是权限：403 时一条 hist 查询都不发")
    void historyChecksPermBeforeQuery() {
        org.mockito.Mockito.doThrow(BizException.of(403, "无权限: " + BaseLabelTemplateService.PERM))
                .when(permService).requirePerm(BaseLabelTemplateService.PERM);

        assertThatThrownBy(() -> svc().history(9001L)).isInstanceOf(BizException.class)
                .hasMessageContaining("无权限");
        verify(histMapper, never()).summaryOf(any());
    }

    @Test
    @DisplayName("history 先确认主模板还在：软删/不存在 → 404，不发摘要 SQL")
    void historyRejectsMissingTemplate() {
        when(mapper.selectById(9001L)).thenReturn(null);

        assertThatThrownBy(() -> svc().history(9001L)).isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
        verify(histMapper, never()).summaryOf(any());
    }

    @Test
    @DisplayName("history 返回摘要原序（SQL 已倒序），并按 createBy 批量注入姓名（realName 缺时回落 username）")
    void historyInjectsNamesInOneBatch() {
        when(mapper.selectById(9001L)).thenReturn(c());
        LabelHistorySummary s2 = new LabelHistorySummary();
        s2.setSeq(2); s2.setCreateBy(42L);
        LabelHistorySummary s1 = new LabelHistorySummary();
        s1.setSeq(1); s1.setCreateBy(7L);
        when(histMapper.summaryOf(9001L)).thenReturn(new ArrayList<>(List.of(s2, s1)));
        SysUser u42 = new SysUser(); u42.setId(42L); u42.setRealName("张三");
        SysUser u7 = new SysUser(); u7.setId(7L); u7.setUsername("王七");   // realName 为空 → 回落 username
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(u42, u7));

        List<LabelHistorySummary> out = svc().history(9001L);

        assertThat(out).extracting(LabelHistorySummary::getSeq).containsExactly(2, 1);
        assertThat(out.get(0).getCreateByName()).isEqualTo("张三");
        assertThat(out.get(1).getCreateByName()).isEqualTo("王七");
        // 批量：一条 selectBatchIds，不许逐行查
        verify(userMapper, org.mockito.Mockito.times(1)).selectBatchIds(any());
    }

    @Test
    @DisplayName("零历史：history 返回空列表，且一次用户表都不查（没行可标注，不值得为它开 SELECT）")
    void historyWithNoRowsNeverQueriesUsers() {
        // 钉的是 CreateByNameInjector :64（records 空）+ :70（userIds 空）两条早退的**合并效果**：
        // 零历史 ⇒ 一次 selectBatchIds 都不许发。单删 :64 这条仍然绿，因为 :70 兜着空列表；
        // 真能红的是把两条一起换成「无条件建集合再查」，那时历史抽屉每次打开都多打一条 SELECT。
        when(mapper.selectById(9001L)).thenReturn(c());
        when(histMapper.summaryOf(9001L)).thenReturn(List.of());

        assertThat(svc().history(9001L)).isEmpty();
        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("姓名全查不到 → createByName 是 null 而不是编出来的名字；createBy 为空的行 setter 根本不碰")
    void historyNeverInventsOperatorNames() {
        when(mapper.selectById(9001L)).thenReturn(c());
        LabelHistorySummary named = new LabelHistorySummary();
        named.setSeq(2);
        named.setCreateBy(42L);
        LabelHistorySummary anonymous = new LabelHistorySummary();
        anonymous.setSeq(1);                                    // 历史脏数据：create_by IS NULL
        anonymous.setCreateByName("这一格不许被动");
        when(histMapper.summaryOf(9001L)).thenReturn(new ArrayList<>(List.of(named, anonymous)));
        SysUser blank = new SysUser();
        blank.setId(42L);
        blank.setRealName("   ");                               // 两列都空白 ⇒ resolveName 返回 null
        blank.setUsername("");
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(blank));

        List<LabelHistorySummary> out = svc().history(9001L);

        // resolveName :98-99：realName、username 都空白就是取不到名字，摘要这一格留 null 给 UI 显示 createBy，
        // 编一个「系统管理员」比留空更坏。createBy 本身必须原样还在，否则连反查的线索都丢了。
        assertThat(out.get(0).getCreateBy()).isEqualTo(42L);
        assertThat(out.get(0).getCreateByName()).isNull();
        // :79 的 uid != null 守卫：createBy 为空的行连 nameMap.get(null) 都不该被喂，
        // 上面那格预置的哨兵值被改成任何东西（包括 null）都说明这条守卫掉了。
        assertThat(out.get(1).getCreateBy()).isNull();
        assertThat(out.get(1).getCreateByName()).isEqualTo("这一格不许被动");
        verify(userMapper, org.mockito.Mockito.times(1)).selectBatchIds(any());
    }

    @Test
    @DisplayName("摘要 JSON 里没有 elements：列表端点不泄漏完整 JSON（只有字节数与元素数）")
    void summaryLeaksNoFullJson() throws Exception {
        Set<String> fields = Arrays.stream(LabelHistorySummary.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic() && !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .map(Field::getName)
                .collect(Collectors.toSet());
        assertThat(fields).containsExactlyInAnyOrder(
                "seq", "createBy", "createByName", "createTime", "elementsBytes", "elementCount");

        LabelHistorySummary s = new LabelHistorySummary();
        s.setSeq(1); s.setCreateBy(42L); s.setCreateByName("张三");
        s.setCreateTime(LocalDateTime.now()); s.setElementsBytes(6L); s.setElementCount(1);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(s);
        // 「elements 原文」这个键不许出现；elementsBytes/elementCount 是摘要自己的统计键，允许。
        assertThat(json).doesNotContain("\"elements\":").contains("\"elementsBytes\":").contains("\"elementCount\":");
    }

    @Test
    @DisplayName("historyOne 按 template_id+seq 普通只读查询，绝不能用 FOR UPDATE 那条")
    void historyOneUsesPlainRead() {
        BaseLabelTemplateHist h = histOf(2, a());
        when(mapper.selectById(9001L)).thenReturn(c());
        when(histMapper.selectVersion(9001L, 2)).thenReturn(h);

        assertThat(svc().historyOne(9001L, 2)).isSameAs(h);
        verify(histMapper, never()).selectVersionForUpdate(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("historyOne：seq 缺失或 ≤0 → 400（服务直接调用也检查），先于任何查询")
    void historyOneRejectsBadSeq() {
        assertThatThrownBy(() -> svc().historyOne(9001L, 0)).isInstanceOf(BizException.class)
                .hasMessageContaining("seq");
        assertThatThrownBy(() -> svc().historyOne(9001L, -1)).isInstanceOf(BizException.class)
                .hasMessageContaining("seq");
        assertThatThrownBy(() -> svc().historyOne(9001L, null)).isInstanceOf(BizException.class)
                .hasMessageContaining("seq");
        verify(histMapper, never()).selectVersion(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("historyOne：别的模板的 seq / 不存在的 seq → 404（查询按 template_id+seq 收窄）")
    void historyOneForeignSeqIs404() {
        when(mapper.selectById(9001L)).thenReturn(c());
        when(histMapper.selectVersion(9001L, 99)).thenReturn(null);

        assertThatThrownBy(() -> svc().historyOne(9001L, 99))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(404);
                    assertThat(e.getMessage()).contains("不存在");
                });
    }

    @Test
    @DisplayName("rollback 第一步是权限：403 时不加行锁、不读历史、不写任何东西")
    void rollbackChecksPermBeforeLocking() {
        org.mockito.Mockito.doThrow(BizException.of(403, "无权限: " + BaseLabelTemplateService.PERM))
                .when(permService).requirePerm(BaseLabelTemplateService.PERM);

        assertThatThrownBy(() -> svc().rollback(9001L, 1)).isInstanceOf(BizException.class)
                .hasMessageContaining("无权限");
        verify(mapper, never()).selectForUpdate(any());
        verify(histMapper, never()).selectVersionForUpdate(any(), org.mockito.ArgumentMatchers.anyInt());
        verify(histMapper, never()).insert(any(BaseLabelTemplateHist.class));
    }

    @Test
    @DisplayName("rollback：seq 缺失或 ≤0 → 400（服务直接调用也检查），先于行锁")
    void rollbackRejectsBadSeqBeforeLocking() {
        assertThatThrownBy(() -> svc().rollback(9001L, 0)).isInstanceOf(BizException.class)
                .hasMessageContaining("seq");
        assertThatThrownBy(() -> svc().rollback(9001L, null)).isInstanceOf(BizException.class)
                .hasMessageContaining("seq");
        verify(mapper, never()).selectForUpdate(any());
    }

    @Test
    @DisplayName("rollback：模板不存在 → 404，不读历史")
    void rollbackMissingTemplateIs404() {
        when(mapper.selectForUpdate(9001L)).thenReturn(null);

        assertThatThrownBy(() -> svc().rollback(9001L, 1)).isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
        verify(histMapper, never()).selectVersionForUpdate(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("rollback：该模板下没有这个 seq（含别的模板的 seq）→ 404，主表和 hist 均不变")
    void rollbackUnknownVersionChangesNothing() {
        when(mapper.selectForUpdate(9001L)).thenReturn(c());
        when(histMapper.selectVersionForUpdate(9001L, 99)).thenReturn(null);

        assertThatThrownBy(() -> svc().rollback(9001L, 99)).isInstanceOf(BizException.class)
                .hasMessageContaining("不存在");
        verify(mapper, never()).updateById(any(BaseLabelTemplate.class));
        verify(histMapper, never()).insert(any(BaseLabelTemplateHist.class));
    }

    @Test
    @DisplayName("rollback seq1：恢复列=历史那 8 列，身份列（名/码/默认位/状态/备注）留在现在；当前行 C 先存成 seq3")
    void rollbackRestoresOnlyAllowedColumnsAndSnapshotsCurrent() {
        BaseLabelTemplate old = c();
        when(mapper.selectForUpdate(9001L)).thenReturn(old);
        when(histMapper.selectVersionForUpdate(9001L, 1)).thenReturn(histOf(1, a()));
        when(histMapper.maxSeq(9001L)).thenReturn(2);            // 已有 seq1=A、seq2=B
        when(mapper.updateById(any(BaseLabelTemplate.class))).thenReturn(1);

        svc().rollback(9001L, 1);

        ArgumentCaptor<BaseLabelTemplate> cap = ArgumentCaptor.forClass(BaseLabelTemplate.class);
        verify(mapper).updateById(cap.capture());
        BaseLabelTemplate next = cap.getValue();
        // 允许恢复列：回到 A 的纸头与元素
        assertThat(next.getBizType()).isEqualTo("FG_ROLL");
        assertThat(next.getWidthMm()).isEqualTo(80);
        assertThat(next.getHeightMm()).isEqualTo(60);
        assertThat(next.getGapMm()).isEqualTo(3);
        assertThat(next.getDpi()).isEqualTo(203);
        assertThat(next.getDirection()).isEqualTo("NORMAL");
        assertThat(next.getCopies()).isEqualTo(1);
        assertThat(next.getElements()).isEqualTo(a().getElements());
        // 不进历史的列：一律保持当前行（C）的值
        assertThat(next.getTemplateName()).isEqualTo("现名");
        assertThat(next.getTemplateCode()).isEqualTo("FG_TEST");
        assertThat(next.getIsDefault()).isEqualTo(1);
        assertThat(next.getStatus()).isEqualTo(0);
        assertThat(next.getRemark()).isEqualTo("现备注");

        // 回滚自身留痕：seq3 = 覆盖前的 C
        BaseLabelTemplateHist snap = capturedHist();
        assertThat(snap.getSeq()).isEqualTo(3);
        assertThat(snap.getElements()).isEqualTo(c().getElements());
        assertThat(snap.getWidthMm()).isEqualTo(70);
    }

    @Test
    @DisplayName("rollback 允许恢复历史里合法的 bizType（与 update 禁止改类型是两条路，各钉各的）")
    void rollbackCanRestoreHistoricalBizType() {
        BaseLabelTemplateHist target = histOf(1, a());
        target.setBizType("CARTON");
        target.setElements("[{\"type\":\"text\",\"x\":10,\"y\":10,\"field\":\"customerName\",\"size\":24}]");
        when(mapper.selectForUpdate(9001L)).thenReturn(c());     // 主表当前是 FG_ROLL
        when(histMapper.selectVersionForUpdate(9001L, 1)).thenReturn(target);
        when(histMapper.maxSeq(9001L)).thenReturn(null);
        when(mapper.updateById(any(BaseLabelTemplate.class))).thenReturn(1);

        svc().rollback(9001L, 1);

        ArgumentCaptor<BaseLabelTemplate> cap = ArgumentCaptor.forClass(BaseLabelTemplate.class);
        verify(mapper).updateById(cap.capture());
        assertThat(cap.getValue().getBizType()).isEqualTo("CARTON");
        assertThat(cap.getValue().getElements()).isEqualTo(target.getElements());
    }

    @Test
    @DisplayName("rollback 也过保存闸：历史版按现行度量越界 → 400，主表与 hist 都不写")
    void rollbackStillPassesTheGate() {
        BaseLabelTemplateHist target = histOf(1, a());
        // 用带正文的静态 text（字段值取空时渲染侧根本不发 TEXT 行，越界不判 —— 见 textOverflow 守卫）
        target.setElements("[{\"type\":\"text\",\"x\":1,\"y\":9000,\"text\":\"甲乙\",\"size\":24}]");
        when(mapper.selectForUpdate(9001L)).thenReturn(c());
        when(histMapper.selectVersionForUpdate(9001L, 1)).thenReturn(target);

        assertThatThrownBy(() -> svc().rollback(9001L, 1)).isInstanceOf(BizException.class)
                .hasMessageContaining("越界");
        verify(mapper, never()).updateById(any(BaseLabelTemplate.class));
        verify(histMapper, never()).insert(any(BaseLabelTemplateHist.class));
    }

    @Test
    @DisplayName("rollback 读历史目标用锁定当前读（selectVersionForUpdate），不是普通 selectVersion")
    void rollbackReadsTargetWithLock() {
        when(mapper.selectForUpdate(9001L)).thenReturn(c());
        when(histMapper.selectVersionForUpdate(9001L, 1)).thenReturn(histOf(1, a()));
        when(histMapper.maxSeq(9001L)).thenReturn(null);
        when(mapper.updateById(any(BaseLabelTemplate.class))).thenReturn(1);

        svc().rollback(9001L, 1);

        verify(histMapper).selectVersionForUpdate(9001L, 1);
        verify(histMapper, never()).selectVersion(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("rollback 与 update 共用同一事务形状：公共入口 READ_COMMITTED + rollbackFor=Exception")
    void rollbackIsTransactionalOnThePublicEntryToo() throws Exception {
        java.lang.reflect.Method rollback = BaseLabelTemplateService.class
                .getMethod("rollback", Long.class, Integer.class);
        org.springframework.transaction.annotation.Transactional tx =
                rollback.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(tx).as("rollback 必须自带 @Transactional，否则主表行锁与 hist 行锁随语句自动提交掉光")
                .isNotNull();
        assertThat(tx.isolation())
                .as("rollback 走 snapshotAndReplace（maxSeq FOR UPDATE），隔离级别必须与 update 同为 READ_COMMITTED")
                .isEqualTo(org.springframework.transaction.annotation.Isolation.READ_COMMITTED);
        assertThat(tx.rollbackFor()).contains(Exception.class);
    }

    /**
     * 反射取 @Select 绑定的 SQL 原文（写法同 {@code QmsProcessInspectionContractTest}）。
     *
     * <p>先钉「恰好一段」再返回文本：注解写成多行拼接时 MyBatis 拿到的仍是编译期常量合并后的
     * 单元素数组，数组长度一变就说明有人改了注解形式，这时候整段断言的意义要先打个问号。
     */
    private static String selectSqlOf(Method m) {
        Select select = m.getAnnotation(Select.class);
        assertThat(select).as("%s 的 SQL 必须由 @Select 注解绑定，否则反射钉不住、真库也跑不到", m.getName())
                .isNotNull();
        assertThat(select.value())
                .as("%s 的 @Select 段数变了，先确认拼接结果再改这里的断言", m.getName())
                .singleElement()
                .asString()
                .contains("template_id = #{templateId}");
        return select.value()[0];
    }

    @Test
    @DisplayName("hist 读 SQL 的形状：锁定读三条真带 FOR UPDATE、只读两条真不带，template_id 恒在")
    void lockingReadsAreActualForUpdateAndPlainReadsAreNot() throws Exception {
        // 写路径 + 回滚目标读：少了 FOR UPDATE 就把「读到最新已提交序号」押在调用方的隔离级别上。
        // 类 javadoc 那句「不要把它们简化成普通 SELECT」到这里才变成机器能查的断言。
        for (Method m : List.of(
                BaseLabelTemplateHistMapper.class.getMethod("maxSeq", Long.class),
                BaseLabelTemplateHistMapper.class.getMethod("retainedIds", Long.class),
                BaseLabelTemplateHistMapper.class.getMethod("selectVersionForUpdate", Long.class, int.class))) {
            assertThat(selectSqlOf(m)).as("%s 是锁定当前读，FOR UPDATE 不许被简化掉", m.getName())
                    .contains("FOR UPDATE");
        }
        // 反过来：列表投影与单版全量是只读路径，故意不加锁——历史抽屉不该排队等一个正在编辑的人。
        // 这条 doesNotContain 就是「别顺手统一」的执行体。
        for (Method m : List.of(
                BaseLabelTemplateHistMapper.class.getMethod("selectVersion", Long.class, int.class),
                BaseLabelTemplateHistMapper.class.getMethod("summaryOf", Long.class))) {
            assertThat(selectSqlOf(m)).as("%s 是故意的普通快照读，不许顺手加上锁", m.getName())
                    .doesNotContain("FOR UPDATE")
                    .doesNotContain("FOR SHARE")
                    .doesNotContain("LOCK IN SHARE MODE");
        }
    }

    /**
     * 把「这个测试配置类不许带 {@code @Configuration}」从 javadoc 里的警告变成断言。
     *
     * <p>带上会怎样：{@code @SpringBootApplication} 的组件扫描连 {@code target/test-classes} 一起扫，
     * 这个类里的 {@code baseLabelTemplateHistMapper} @Bean 就和 {@code @MapperScan} 已经注册的同名
     * bean 撞车，抛 {@code ConflictingBeanDefinitionException} —— 崩的是跑全量上下文的那些 IT
     * （Task 6 第一次全量 verify 时撞到的是 {@code InvLedgerQueryMapperMysqlIT}），
     * 报错看着却像别人的回归。
     *
     * <p>lite mode 不削弱任何东西：{@code @ContextConfiguration(classes = ...)} 照样处理 @Bean 方法
     * （该类带 {@code @EnableTransactionManagement}，其 meta-{@code @Import} 让它被识别），
     * 且所有依赖都从方法参数进，没有 bean 互调 ⇒ 不需要 {@code proxyBeanMethods}。
     */
    @Test
    @DisplayName("LabelSnapshotTestConfig 必须保持 lite mode：不带 @Configuration")
    void testConfigStaysLiteMode() {
        assertThat(LabelSnapshotTestConfig.class.isAnnotationPresent(Configuration.class))
                .as("见方法 javadoc：@Configuration 会让测试配置被主应用组件扫描到，"
                        + "与 @MapperScan 的同名 mapper bean 冲突，把全栈 IT 一起拖崩")
                .isFalse();
    }
}
