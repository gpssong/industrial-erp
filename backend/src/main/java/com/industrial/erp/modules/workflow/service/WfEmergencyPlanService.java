package com.industrial.erp.modules.workflow.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseTenant;
import com.industrial.erp.modules.base.mapper.BaseTenantMapper;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.modules.workflow.entity.WfEmergencyDetail;
import com.industrial.erp.modules.workflow.entity.WfEmergencyPlan;
import com.industrial.erp.modules.workflow.mapper.WfEmergencyDetailMapper;
import com.industrial.erp.modules.workflow.mapper.WfEmergencyPlanMapper;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** v1.1.79 工作流: 应急预案服务 (含租客, 简单台账 + 审核/反审核) */
@Service
public class WfEmergencyPlanService {

    public WfEmergencyPlanService(WfEmergencyPlanMapper planMapper, WfEmergencyDetailMapper detailMapper,
                                 BaseTenantMapper tenantMapper, BillNoGenerator billNoGenerator,
                                 PermissionService permService, OperLogPublisher operLogPublisher) {
        this.planMapper = planMapper;
        this.detailMapper = detailMapper;
        this.tenantMapper = tenantMapper;
        this.billNoGenerator = billNoGenerator;
        this.permService = permService;
        this.operLogPublisher = operLogPublisher;
    }

    private final WfEmergencyPlanMapper planMapper;
    private final WfEmergencyDetailMapper detailMapper;
    private final BaseTenantMapper tenantMapper;
    private final BillNoGenerator billNoGenerator;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;

    public IPage<WfEmergencyPlan> page(Integer pageNum, Integer pageSize, Long tenantId, String billStatus, Boolean overdue) {
        permService.requirePerm("work:emergency:list");
        Page<WfEmergencyPlan> p = new Page<>(pageNum, pageSize);
        return planMapper.selectPageList(p, tenantId, billStatus, overdue);
    }

    public WfEmergencyPlan detail(Long id) {
        WfEmergencyPlan r = planMapper.selectById(id);
        if (r != null) r.setDetails(detailMapper.selectByPlanId(id));
        return r;
    }

    @OperLog(module = "应急预案", businessType = "ADD", saveParam = true)
    @Transactional(rollbackFor = Exception.class)
    public void add(WfEmergencyPlan r) {
        permService.requirePerm("work:emergency:add");
        if (StrUtil.isBlank(r.getPlanNo())) r.setPlanNo(billNoGenerator.generate(Constants.BILL_EP));
        if (StrUtil.isBlank(r.getBillStatus())) r.setBillStatus(Constants.STATUS_DRAFT);
        if (r.getTenantId() != null) {
            BaseTenant t = tenantMapper.selectById(r.getTenantId());
            if (t == null) throw BizException.of("租客不存在");
            r.setTenantName(t.getTenantName());
        }
        planMapper.insert(r);
        insertDetails(r);
    }

    @OperLog(module = "应急预案", businessType = "EDIT", saveParam = true)
    @Transactional(rollbackFor = Exception.class)
    public void update(WfEmergencyPlan r) {
        permService.requirePerm("work:emergency:edit");
        WfEmergencyPlan origin = planMapper.selectById(r.getId());
        if (origin == null) throw BizException.of("预案不存在");
        if (!Constants.STATUS_DRAFT.equals(origin.getBillStatus())) throw BizException.of("只有草稿状态可修改");
        r.setPlanNo(origin.getPlanNo());
        r.setBillStatus(origin.getBillStatus());
        if (r.getTenantId() != null) {
            BaseTenant t = tenantMapper.selectById(r.getTenantId());
            if (t == null) throw BizException.of("租客不存在");
            r.setTenantName(t.getTenantName());
        } else {
            r.setTenantName(null);
        }
        planMapper.updateById(r);
        detailMapper.delete(new LambdaQueryWrapper<WfEmergencyDetail>().eq(WfEmergencyDetail::getPlanId, r.getId()));
        insertDetails(r);
    }

    private void insertDetails(WfEmergencyPlan r) {
        if (r.getDetails() == null) return;
        int step = 0;
        for (WfEmergencyDetail d : r.getDetails()) {
            d.setId(null);
            d.setPlanId(r.getId());
            if (d.getStepNo() == null) d.setStepNo(++step);
            else step = Math.max(step, d.getStepNo());
            detailMapper.insert(d);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        permService.requirePerm("work:emergency:delete");
        WfEmergencyPlan r = planMapper.selectById(id);
        if (r == null) throw BizException.of("预案不存在");
        if (!Constants.STATUS_DRAFT.equals(r.getBillStatus())) throw BizException.of("只有草稿状态可删除");
        List<WfEmergencyDetail> details = detailMapper.selectByPlanId(id);
        detailMapper.update(null, new LambdaUpdateWrapper<WfEmergencyDetail>()
                .eq(WfEmergencyDetail::getPlanId, id).set(WfEmergencyDetail::getDeleted, 1));
        planMapper.update(null, new LambdaUpdateWrapper<WfEmergencyPlan>()
                .eq(WfEmergencyPlan::getId, id).set(WfEmergencyPlan::getDeleted, 1));
        operLogPublisher.publishDeleteSnapshot("应急预案", String.valueOf(id), r, details);
    }

    @OperLog(module = "应急预案", businessType = "CHECK", saveParam = true)
    @Transactional(rollbackFor = Exception.class)
    public void check(Long id) {
        permService.requirePerm("work:emergency:check");
        WfEmergencyPlan r = planMapper.selectById(id);
        if (r == null) throw BizException.of("预案不存在");
        if (!Constants.STATUS_DRAFT.equals(r.getBillStatus())) throw BizException.of("只有草稿状态可审核");
        WfEmergencyPlan upd = new WfEmergencyPlan();
        upd.setId(id);
        upd.setBillStatus(Constants.STATUS_CHECKED);
        planMapper.updateById(upd);
    }

    @OperLog(module = "应急预案", businessType = "UNCHECK", saveParam = true)
    @Transactional(rollbackFor = Exception.class)
    public void uncheck(Long id) {
        permService.requirePerm("work:emergency:uncheck");
        WfEmergencyPlan r = planMapper.selectById(id);
        if (r == null) throw BizException.of("预案不存在");
        if (!Constants.STATUS_CHECKED.equals(r.getBillStatus())) throw BizException.of("只有已审核状态可反审核");
        WfEmergencyPlan upd = new WfEmergencyPlan();
        upd.setId(id);
        upd.setBillStatus(Constants.STATUS_DRAFT);
        planMapper.updateById(upd);
    }
}
