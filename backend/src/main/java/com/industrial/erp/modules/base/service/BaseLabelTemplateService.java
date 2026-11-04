package com.industrial.erp.modules.base.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import com.industrial.erp.modules.base.label.LabelTemplateRenderer;
import com.industrial.erp.modules.base.label.ValidationIssue;
import com.industrial.erp.modules.base.mapper.BaseLabelTemplateMapper;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * v1.1.167 标签模板增删改查 (docs/32 §6.1)。
 *
 * <p>**保存即校验**：元素 JSON 的未知字段名、越界、坏 JSON 一律在 add/update 里拦掉，
 * 不留到打印那一刻（v1.1.162 飞鹅模板语法闸同一思路）。
 */
@Service
public class BaseLabelTemplateService {

    public static final String PERM = "base:label:template";

    public BaseLabelTemplateService(BaseLabelTemplateMapper mapper, LabelTemplateRenderer renderer,
                                    PermissionService permService) {
        this.mapper = mapper;
        this.renderer = renderer;
        this.permService = permService;
    }

    private final BaseLabelTemplateMapper mapper;
    private final LabelTemplateRenderer renderer;
    private final PermissionService permService;

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

    public void update(BaseLabelTemplate t) {
        permService.requirePerm(PERM);
        // **A8 修补轮：** id 缺失是客户端的错，回 400；别让 detail 报「不存在或已删除: id=null」那种 404。
        if (t.getId() == null) throw BizException.of(400, "缺少模板 id");
        BaseLabelTemplate old = detail(t.getId());
        // **A8 裁定 R2：先把行的身份钉死，再过闸。** bizType 决定「哪些字段名算合法」，
        // 原来 gate(t) 排在回写之前 ⇒ 客户端 PUT 时偷带一个别的 bizType，元素就会按**伪造类型**的字典过闸，
        // 落库却仍是 old 的类型 —— 闸撒了谎（正是 v1.1.162 那次「预览过了怎么打不出来」的成因类别）。
        // 顺带：applyDefaults 的 bizType 非空检查因此只对 add 有意义，这里不会误报。
        t.setBizType(old.getBizType());        // bizType 决定了合法字段名，不许改来改去
        t.setIsDefault(old.getIsDefault());    // 默认位只能走 setDefault
        applyDefaults(t);
        requireUniqueCode(t.getTemplateCode(), t.getId());
        gate(t);
        mapper.updateById(t);
    }

    public void delete(Long id) {
        permService.requirePerm(PERM);
        detail(id);
        mapper.update(null, new LambdaUpdateWrapper<BaseLabelTemplate>()
                .eq(BaseLabelTemplate::getId, id).set(BaseLabelTemplate::getDeleted, 1));
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
        if (t.getWidthMm() == null) t.setWidthMm(80);
        if (t.getHeightMm() == null) t.setHeightMm(60);
        if (t.getGapMm() == null) t.setGapMm(3);
        if (t.getDpi() == null) t.setDpi(203);
        if (t.getCopies() == null) t.setCopies(1);
        if (t.getStatus() == null) t.setStatus(1);
        if (t.getDirection() == null || t.getDirection().isBlank()) t.setDirection("NORMAL");
        if (t.getBizType() == null || t.getBizType().isBlank()) throw BizException.of(400, "请选择标签类型");
        if (t.getTemplateName() == null || t.getTemplateName().isBlank()) throw BizException.of(400, "请填写模板名称");
        if (t.getTemplateCode() == null || t.getTemplateCode().isBlank()) throw BizException.of(400, "请填写模板编码");
        // **A8 裁定 R3：纸头数字必须是正数，先于闸。** `validate` 的签名收的是原始 int，
        // dpi=0 或 width=0 会让画布变成 0×0 ⇒ 每个元素都被判「越过下边界 N 点」，
        // 把「表头填了 0」这种错**归罪到元素坐标上** —— 又是归因错。闸只管元素，表头归这里管。
        if (t.getWidthMm() <= 0 || t.getHeightMm() <= 0) throw BizException.of(400, "标签宽/高必须大于 0 mm");
        if (t.getDpi() <= 0) throw BizException.of(400, "DPI 必须大于 0");
    }

    private void requireUniqueCode(String code, Long selfId) {
        Long n = mapper.selectCount(new LambdaQueryWrapper<BaseLabelTemplate>()
                .eq(BaseLabelTemplate::getTemplateCode, code)
                .ne(selfId != null, BaseLabelTemplate::getId, selfId));
        if (n != null && n > 0) throw BizException.of(400, "模板编码已存在: " + code);
    }
}
