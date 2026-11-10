package com.industrial.erp.modules.base.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.industrial.erp.common.CreateByNameInjector;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import com.industrial.erp.modules.base.entity.BaseLabelTemplateHist;
import com.industrial.erp.modules.base.label.LabelGeometry;
import com.industrial.erp.modules.base.label.LabelHistorySummary;
import com.industrial.erp.modules.base.label.LabelTemplateRenderer;
import com.industrial.erp.modules.base.label.ValidationIssue;
import com.industrial.erp.modules.base.mapper.BaseLabelTemplateHistMapper;
import com.industrial.erp.modules.base.mapper.BaseLabelTemplateMapper;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * v1.1.167 标签模板增删改查 (docs/32 §6.1)。
 *
 * <p>**保存即校验**：元素 JSON 的未知字段名、越界、坏 JSON 一律在 add/update 里拦掉，
 * 不留到打印那一刻（v1.1.162 飞鹅模板语法闸同一思路）。
 *
 * <p>**update 会留史**：覆盖之前先把旧行的纸头与元素抄进 {@code base_label_template_hist}，
 * 每个模板只留最近 20 版（docs/36 Task 6）。这条路径必须在**一个事务 + 一次行锁**里走完，
 * 所以 {@link #update} 上的 {@code @Transactional} 不是装饰；add 与 delete 不写历史 ——
 * 历史回答的是「当时把什么改掉了」，新建那一版没有旧内容可抄。
 */
@Service
public class BaseLabelTemplateService {

    public static final String PERM = "base:label:template";

    public BaseLabelTemplateService(BaseLabelTemplateMapper mapper, LabelTemplateRenderer renderer,
                                    PermissionService permService, BaseLabelTemplateHistMapper histMapper,
                                    SysUserMapper userMapper) {
        this.mapper = mapper;
        this.renderer = renderer;
        this.permService = permService;
        this.histMapper = histMapper;
        this.userMapper = userMapper;
    }

    private final BaseLabelTemplateMapper mapper;
    private final LabelTemplateRenderer renderer;
    private final PermissionService permService;
    private final BaseLabelTemplateHistMapper histMapper;
    private final SysUserMapper userMapper;

    public List<BaseLabelTemplate> list(String bizType, Integer status) {
        return mapper.selectList(new LambdaQueryWrapper<BaseLabelTemplate>()
                .eq(bizType != null && !bizType.isBlank(), BaseLabelTemplate::getBizType, bizType)
                .eq(status != null, BaseLabelTemplate::getStatus, status)
                .orderByDesc(BaseLabelTemplate::getIsDefault)
                .orderByAsc(BaseLabelTemplate::getBizType)
                .orderByDesc(BaseLabelTemplate::getId));
    }

    public BaseLabelTemplate detail(Long id) {
        BaseLabelTemplate t = mapper.selectById(id);
        if (t == null) throw BizException.of(404, "标签模板不存在或已删除: id=" + id);
        return t;
    }

    /** 该类型的默认模板；没有则 null（前端提示「先设一份默认模板」）。 */
    public BaseLabelTemplate defaultOf(String bizType) {
        return mapper.selectOne(new LambdaQueryWrapper<BaseLabelTemplate>()
                .eq(BaseLabelTemplate::getBizType, bizType)
                .eq(BaseLabelTemplate::getIsDefault, 1)
                .eq(BaseLabelTemplate::getStatus, 1)
                .orderByDesc(BaseLabelTemplate::getUpdateTime)
                .last("LIMIT 1"));
    }

    public void add(BaseLabelTemplate t) {
        permService.requirePerm(PERM);
        applyDefaults(t);
        requireUniqueCode(t.getTemplateCode(), null);
        gate(t);
        t.setId(null);
        // **A8 裁定 R6（评审 I-1）：新建一律不是默认，默认位只能走 setDefault。**
        // 照客户传的 isDefault=1 直接 INSERT，会在 setDefault 的清兄弟逻辑背后插出第二条默认。
        t.setIsDefault(0);
        mapper.insert(t);
    }

    // **隔离级别必须是 READ COMMITTED，不能用 MySQL 默认的 REPEATABLE READ。** maxSeq 那条
    // `ORDER BY seq DESC LIMIT 1 FOR UPDATE` 在某模板还没有历史时扫的是**不命中的区间**，RR 下会
    // 在二级索引上留下 gap 锁；gap 锁彼此相容，于是两个模板各自锁完自己的空档，再插入时都要拿
    // insert-intention 锁，而它和对方的 gap 锁冲突 —— 结果互相死锁（1213），一次保存必失败。
    // 实测（可复现）：RR 下 T1/T2 分别锁 template 101/102 的空档后串行插入，
    // T1 报 Deadlock found；READ COMMITTED 下同样时序两次插入都成功。RC 关掉 gap 锁，但
    // FOR UPDATE 仍是当前读，同模板串行化靠的是主表行锁（selectForUpdate），一点没少。
    // 钉住这条的就是 LabelSnapshotIT.differentTemplatesNumberIndependently —— 它不重试、
    // 不换模板 id，两次保存必须都成功。
    // 代价只有一条：RC 要求 binlog_format=ROW（本库已是 ROW），STATEMENT 下会把 seq 复制乱。
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void update(BaseLabelTemplate next) {
        permService.requirePerm(PERM);
        // **A8 修补轮：** id 缺失是客户端的错，回 400；别让读旧行报「不存在或已删除: id=null」那种 404。
        if (next.getId() == null) throw BizException.of(400, "缺少模板 id");
        // **旧行必须是加锁读，不是 detail()。** 两个人同时编辑同一份模板时，各自按自己读到的旧行
        // 生成历史，就会出现两条 seq 相同、内容却都不是「上一次落库的那版」的历史 —— 历史链一断，
        // 回滚哪一条都对不上现在的主表。行锁把后到的编辑挡到前一个提交之后，读到的才是真的旧行。
        BaseLabelTemplate old = mapper.selectForUpdate(next.getId());
        if (old == null) throw BizException.of(404, "标签模板不存在或已删除: id=" + next.getId());
        // **A8 裁定 R2：先把行的身份钉死，再过闸。** bizType 决定「哪些字段名算合法」，
        // 原来 gate(t) 排在回写之前 ⇒ 客户端 PUT 时偷带一个别的 bizType，元素就会按**伪造类型**的字典过闸，
        // 落库却仍是 old 的类型 —— 闸撒了谎（正是 v1.1.162 那次「预览过了怎么打不出来」的成因类别）。
        // 顺带：applyDefaults 的 bizType 非空检查因此只对 add 有意义，这里不会误报。
        next.setBizType(old.getBizType());        // bizType 决定了合法字段名，不许改来改去
        next.setIsDefault(old.getIsDefault());    // 默认位只能走 setDefault
        applyDefaults(next);
        requireUniqueCode(next.getTemplateCode(), next.getId());
        gate(next);
        // 闸没过就一行都不写 —— 历史上不该出现「坏排版」那一版，否则回滚等于把用户往回坑。
        snapshotAndReplace(old, next);
    }

    /**
     * 抄快照 → 定 seq → 写史 → 覆盖主表（必须恰好 1 行）→ 裁到最近 20 版。
     *
     * <p>**只能是私有方法，而且事务边界在 {@link #update} 上。** 私有方法上的 {@code @Transactional}
     * 不会被 Spring 代理（自调用不走代理），标在这儿等于没标；反过来把 {@code update} 的事务去掉，
     * {@code selectForUpdate} 的行锁就会随语句自动提交当场掉光，串行化整个是空的。
     *
     * <p>顺序也有讲究：裁剪排在主表覆盖**之后**。第 21 次更新失败时，前面已提交的 20 版
     * 一条都不该被裁掉（{@code LabelSnapshotIT.failureAtTheTwentyFirstUpdate...} 钉的就是这个）。
     *
     * <p>任何一步抛异常都整体回滚：不留「主表没改、历史多一版」的孤儿，也不留「历史写失败、
     * 主表已经改了」的断链。并发最后提交的那份编辑仍然是正常的 last-write-wins，
     * 不另加乐观锁或版本号字段 —— 但每次覆盖之前的实际旧内容都完整留了史。
     */
    private void snapshotAndReplace(BaseLabelTemplate old, BaseLabelTemplate next) {
        BaseLabelTemplateHist hist = new BaseLabelTemplateHist();
        hist.setTemplateId(old.getId());
        hist.setBizType(old.getBizType());
        hist.setWidthMm(old.getWidthMm());
        hist.setHeightMm(old.getHeightMm());
        hist.setGapMm(old.getGapMm());
        hist.setDpi(old.getDpi());
        hist.setDirection(old.getDirection());
        hist.setCopies(old.getCopies());
        hist.setElements(old.getElements());

        // seq 在锁内取**当前**最大值 +1；没有历史时 maxSeq 回 null，按 0 处理。
        // 不能用普通 MAX() —— 那是快照读，会读到进入等待之前旧版本的历史条数，两个编辑算出同一个 seq。
        Integer currentMax = histMapper.maxSeq(old.getId());
        hist.setSeq((currentMax == null ? 0 : currentMax) + 1);
        // 操作者与时刻都是「这一次覆盖」的，不是旧行的：旧行的 create_by 是「谁建的模板」，那是另一回事。
        hist.setCreateBy(permService.getCurrentUserId());
        hist.setCreateTime(LocalDateTime.now());
        histMapper.insert(hist);

        // 返回 0（行被硬删了）或 >1（条件写歪了）都算失败：绝不能不检查就往下走，
        // 那等于对着用户报「保存成功」而库里一个字都没变。
        int affected = mapper.updateById(next);
        if (affected != 1) {
            throw BizException.of(500, "标签模板保存未生效: id=" + next.getId() + "（影响 " + affected + " 行）");
        }

        // 插入之后查 20 个保留 id 再删其余：刚写的那一版一定在保留集合里，这一点由库的排序自己保证。
        // 保留集合为空时**不发**删除（NOT IN () 是语法错误），也删不出东西。
        List<Long> retained = histMapper.retainedIds(old.getId());
        if (retained != null && !retained.isEmpty()) {
            histMapper.deleteExcept(old.getId(), retained);
        }
    }

    public void delete(Long id) {
        permService.requirePerm(PERM);
        detail(id);
        mapper.update(null, new LambdaUpdateWrapper<BaseLabelTemplate>()
                .eq(BaseLabelTemplate::getId, id).set(BaseLabelTemplate::getDeleted, 1));
    }

    // ────────────────────────── Task 7：历史摘要 / 详情 / 可再次回滚 ──────────────────────────

    /**
     * 最近 20 版的摘要（倒序）；只有纸头统计与操作者，不回 elements 原文。
     *
     * <p>姓名走 {@code CreateByNameInjector} 一次批量换，取不到就留 null：UI 还能显示 createBy，
     * 伪造一个「系统管理员」比留空更坏。先确认主模板没被软删 —— 历史表没有 deleted 列，
     * 主行删了之后这里若不加检查，等于给已下线模板继续开历史入口。
     */
    public List<LabelHistorySummary> history(Long id) {
        permService.requirePerm(PERM);
        detail(id);
        List<LabelHistorySummary> rows = histMapper.summaryOf(id);
        CreateByNameInjector.inject(userMapper, rows,
                LabelHistorySummary::getCreateBy, LabelHistorySummary::setCreateByName);
        return rows;
    }

    /**
     * 单版全量（含 elements）。**按 template_id + seq 双条件查**，绝不按 seq 单查 ——
     * seq 的作用域是模板自己，别的模板的「第 2 版」在这份历史里不存在。
     */
    public BaseLabelTemplateHist historyOne(Long id, Integer seq) {
        permService.requirePerm(PERM);
        requireSeq(seq);
        detail(id);
        BaseLabelTemplateHist hist = histMapper.selectVersion(id, seq);
        if (hist == null) {
            throw BizException.of(404, "历史版本不存在或已被裁剪: template_id=" + id + ", seq=" + seq);
        }
        return hist;
    }

    /**
     * 回滚 = 再更新一次：先把当前行存成新快照，再把历史那 8 列（bizType/纸头/elements）覆盖回主表。
     * 所以回滚本身可以再回滚（docs/35 §7.4）。名称/编码/默认位/状态/备注**不进历史也不被恢复** ——
     * 回滚要改的是「这张纸长什么样」，不是「这张纸叫什么」；顺带默认位被旧值拽回去更糟。
     *
     * <p>事务形状与 {@link #update} 完全共用：同一条 {@code snapshotAndReplace}，同一个
     * READ_COMMITTED（理由见 update 上方注释），主表与 hist 行都锁读。目标历史必须在
     * 本次裁剪**之前**读到内存 —— 回滚到最旧一版时，那一版会恰好被自己生成的新快照挤出去，
     * 已读的副本不受影响。历史版是按当时度量存的，按现行度量可能越界 ⇒ 照过闸，没过就不写。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void rollback(Long id, Integer seq) {
        permService.requirePerm(PERM);
        requireSeq(seq);
        BaseLabelTemplate old = mapper.selectForUpdate(id);
        if (old == null) throw BizException.of(404, "标签模板不存在或已删除: id=" + id);
        BaseLabelTemplateHist target = histMapper.selectVersionForUpdate(id, seq);
        if (target == null) {
            throw BizException.of(404, "历史版本不存在或已被裁剪: template_id=" + id + ", seq=" + seq);
        }
        BaseLabelTemplate next = new BaseLabelTemplate();
        next.setId(old.getId());
        next.setTemplateCode(old.getTemplateCode());
        next.setTemplateName(old.getTemplateName());
        next.setIsDefault(old.getIsDefault());
        next.setStatus(old.getStatus());
        next.setRemark(old.getRemark());
        next.setCreateBy(old.getCreateBy());
        next.setCreateTime(old.getCreateTime());
        next.setBizType(target.getBizType());
        next.setWidthMm(target.getWidthMm());
        next.setHeightMm(target.getHeightMm());
        next.setGapMm(target.getGapMm());
        next.setDpi(target.getDpi());
        next.setDirection(target.getDirection());
        next.setCopies(target.getCopies());
        next.setElements(target.getElements());
        applyDefaults(next);
        gate(next);
        snapshotAndReplace(old, next);
    }

    /** 服务直接调用也要挡非法 seq（Controller 的 @Valid 只管 HTTP 入口，不管内部调用方）。 */
    private void requireSeq(Integer seq) {
        if (seq == null || seq < 1) throw BizException.of(400, "缺少或非法的历史序号 seq: " + seq);
    }

    @Transactional
    public void setDefault(Long id) {
        permService.requirePerm(PERM);
        BaseLabelTemplate t = detail(id);
        gate(t);
        mapper.update(null, new LambdaUpdateWrapper<BaseLabelTemplate>()
                .eq(BaseLabelTemplate::getBizType, t.getBizType())
                .ne(BaseLabelTemplate::getId, id)
                .set(BaseLabelTemplate::getIsDefault, 0));
        t.setIsDefault(1);
        mapper.updateById(t);
    }

    /** 保存闸：把 ValidationIssue 拼成人话，第一条就够定位（元素下标从 0 起，PC 表格里同号）。 */
    private void gate(BaseLabelTemplate t) {
        // **A8 修补轮：空模板也是「存得下、打不出」。** `validate` 对 "[]" 回 0 条 issue（它只管元素，
        // A6 那 8 条钉子钉死了这个口径，别去改它）⇒ 零元素模板能存成默认、打出来一张空白标签。
        String el = t.getElements() == null ? "" : t.getElements().trim();
        if (el.isEmpty() || el.equals("[]")) throw BizException.of(400, "模板里还没有任何元素");
        List<ValidationIssue> issues = renderer.validate(t.getElements(), t.getBizType(),
                t.getWidthMm(), t.getHeightMm(), t.getDpi());
        if (!issues.isEmpty()) {
            String msg = issues.stream().map(i -> (i.index() < 0 ? "" : "第 " + (i.index() + 1) + " 个元素: ")
                    + i.message()).collect(Collectors.joining("; "));
            throw BizException.of(400, "模板校验未通过 -> " + msg);
        }
    }

    private void applyDefaults(BaseLabelTemplate t) {
        applyPaperDefaults(t);
        if (t.getStatus() == null) t.setStatus(1);
        if (t.getBizType() == null || t.getBizType().isBlank()) throw BizException.of(400, "请选择标签类型");
        if (t.getTemplateName() == null || t.getTemplateName().isBlank()) throw BizException.of(400, "请填写模板名称");
        if (t.getTemplateCode() == null || t.getTemplateCode().isBlank()) throw BizException.of(400, "请填写模板编码");
        validatePaper(t);
    }

    /**
     * 纸头缺省回填（add/update 与 resolve 共用，docs/35 §3.2 要点 2）。名称/编码/status 一律不在这儿：
     * 草稿没有名称编码，拿假值来调 {@link #validatePaper} 就等于把旧必填绕过去了。
     */
    public static void applyPaperDefaults(BaseLabelTemplate t) {
        if (t.getWidthMm() == null) t.setWidthMm(80);
        if (t.getHeightMm() == null) t.setHeightMm(60);
        if (t.getGapMm() == null) t.setGapMm(3);
        if (t.getDpi() == null) t.setDpi(203);
        if (t.getCopies() == null) t.setCopies(1);
        if (t.getDirection() == null || t.getDirection().isBlank()) t.setDirection("NORMAL");
    }

    /**
     * 纸头合法性（A8 裁定 R3 + docs/36 §0.3）。抽成 public static 是因为 resolve 必须用**同一套数**，
     * 两条路径各判一遍就是「草稿能过、保存不能过」（docs/35 §3.2 要点 2）。
     *
     * <p>正宽高/正 dpi 的文案与顺序照 add/update 原样，容量那条排在它们后面。比较升 long：
     * dpi 已经判正，宽再大也只是换算点数超线，不该靠 int 溢出混过去（A6 I-2 同一条规矩）。
     */
    public static void validatePaper(BaseLabelTemplate t) {
        // **A8 裁定 R3：纸头数字必须是正数，先于闸。** `validate` 的签名收的是原始 int，
        // dpi=0 或 width=0 会让画布变成 0×0 ⇒ 每个元素都被判「越过下边界 N 点」，
        // 把「表头填了 0」这种错**归罪到元素坐标上** —— 又是归因错。闸只管元素，表头归这里管。
        if (t.getWidthMm() == null || t.getHeightMm() == null
                || t.getWidthMm() <= 0 || t.getHeightMm() <= 0) {
            throw BizException.of(400, "标签宽/高必须大于 0 mm");
        }
        if (t.getDpi() == null || t.getDpi() <= 0) throw BizException.of(400, "DPI 必须大于 0");
        if (LabelGeometry.dots(t.getWidthMm(), t.getDpi()) > LabelGeometry.MAX_GEOMETRY_DOTS
                || LabelGeometry.dots(t.getHeightMm(), t.getDpi()) > LabelGeometry.MAX_GEOMETRY_DOTS) {
            throw BizException.of(400, "标签画布不能超过 " + LabelGeometry.MAX_GEOMETRY_DOTS + " 点");
        }
    }

    private void requireUniqueCode(String code, Long selfId) {
        Long n = mapper.selectCount(new LambdaQueryWrapper<BaseLabelTemplate>()
                .eq(BaseLabelTemplate::getTemplateCode, code)
                .ne(selfId != null, BaseLabelTemplate::getId, selfId));
        if (n != null && n > 0) throw BizException.of(400, "模板编码已存在: " + code);
    }
}
