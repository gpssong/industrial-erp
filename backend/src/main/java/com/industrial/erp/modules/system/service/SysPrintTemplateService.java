package com.industrial.erp.modules.system.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.modules.system.dto.PrintTemplateQuery;
import com.industrial.erp.modules.system.entity.SysPrintTemplate;
import com.industrial.erp.modules.system.mapper.SysPrintTemplateMapper;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

@Service
public class SysPrintTemplateService {

    /** 业务类型白名单 */
    public static final Set<String> BIZ_TYPES = Set.of(
            "SAL_DELIVERY", "PUR_RECEIPT", "PUR_RETURN", "SAL_RETURN", "PRD_ORDER"
    );

    public SysPrintTemplateService(SysPrintTemplateMapper mapper,
                                    PermissionService permService,
                                    OperLogPublisher operLogPublisher) {
        this.mapper = mapper;
        this.permService = permService;
        this.operLogPublisher = operLogPublisher;
    }

    private final SysPrintTemplateMapper mapper;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;

    public IPage<SysPrintTemplate> page(PrintTemplateQuery q) {
        permService.requirePerm("system:print:list");
        Page<SysPrintTemplate> p = new Page<>(q.getPageNum(), q.getPageSize());
        LambdaQueryWrapper<SysPrintTemplate> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(q.getName()))   w.like(SysPrintTemplate::getName, q.getName());
        if (StrUtil.isNotBlank(q.getBizType())) w.eq(SysPrintTemplate::getBizType, q.getBizType());
        if (q.getStatus() != null)              w.eq(SysPrintTemplate::getStatus, q.getStatus());
        w.orderByDesc(SysPrintTemplate::getId);
        return mapper.selectPage(p, w);
    }

    public SysPrintTemplate detail(Long id) {
        permService.requirePerm("system:print:list");
        return mapper.selectById(id);
    }

    public SysPrintTemplate getActiveByBizType(String bizType) {
        if (!SecurityContextPermitted()) return null;
        if (!BIZ_TYPES.contains(bizType)) return null;
        List<SysPrintTemplate> list = mapper.selectActiveByBizType(bizType);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 简化: getActiveByBizType 不要求 perm (前端打印按钮会触发, 不应被权限拦截模板查询) */
    private boolean SecurityContextPermitted() {
        try { return com.industrial.erp.security.SecurityContext.isLogin(); }
        catch (Exception e) { return false; }
    }

    @Transactional(rollbackFor = Exception.class)
    public void add(SysPrintTemplate t) {
        permService.requirePerm("system:print:add");
        validate(t);
        applyDefaultFlag(t);
        if (t.getPaperWidth() == null)  t.setPaperWidth(new BigDecimal("210.00"));
        if (t.getPaperHeight() == null) t.setPaperHeight(new BigDecimal("297.00"));
        if (StrUtil.isBlank(t.getPageUnit())) t.setPageUnit("mm");
        if (t.getStatus() == null)    t.setStatus(1);
        if (t.getIsDefault() == null) t.setIsDefault(0);
        mapper.insert(t);
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(SysPrintTemplate t) {
        permService.requirePerm("system:print:edit");
        if (t.getId() == null) throw BizException.of("模板ID不能为空");
        validate(t);
        applyDefaultFlag(t);
        mapper.updateById(t);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        permService.requirePerm("system:print:delete");
        SysPrintTemplate t = mapper.selectById(id);
        if (t == null) throw BizException.of("打印模板不存在或已删除");
        mapper.update(null, new LambdaUpdateWrapper<SysPrintTemplate>()
                .eq(SysPrintTemplate::getId, id).set(SysPrintTemplate::getDeleted, 1));
        operLogPublisher.publishDeleteSnapshot("打印模板", String.valueOf(id), t, null);
    }

    private void validate(SysPrintTemplate t) {
        if (StrUtil.isBlank(t.getName()))     throw BizException.of("模板名称不能为空");
        if (StrUtil.isBlank(t.getBizType()))  throw BizException.of("业务类型不能为空");
        if (!BIZ_TYPES.contains(t.getBizType())) throw BizException.of("不支持的业务类型: " + t.getBizType());
        if (StrUtil.isBlank(t.getContent()))  throw BizException.of("模板内容不能为空");
    }

    /**
     * 若 is_default=1, 把同 biz_type 下其他模板的 is_default 置 0
     */
    private void applyDefaultFlag(SysPrintTemplate t) {
        if (t.getIsDefault() == null) t.setIsDefault(0);
        if (t.getIsDefault() == 1) {
            mapper.update(null, new LambdaUpdateWrapper<SysPrintTemplate>()
                    .eq(SysPrintTemplate::getBizType, t.getBizType())
                    .eq(SysPrintTemplate::getDeleted, 0)
                    .ne(SysPrintTemplate::getId, t.getId() == null ? -1L : t.getId())
                    .set(SysPrintTemplate::getIsDefault, 0));
        }
    }
}