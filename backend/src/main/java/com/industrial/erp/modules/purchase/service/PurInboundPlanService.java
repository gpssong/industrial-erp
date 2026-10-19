package com.industrial.erp.modules.purchase.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.purchase.entity.PurInboundPlan;
import com.industrial.erp.modules.purchase.mapper.PurInboundPlanMapper;
import com.industrial.erp.utils.BillNoGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * v1.1.101 XK — 采购计划 service.
 *
 * <p>承载到货检验 ① 步上下文: App 端 扫码入库 (pages/scan/in) 接收 planId,
 * 自动 loadPlan + loadIqc. 计划→到货→IQC→入库 三步状态机: PLANNED → ARRIVED → INSPECTED → INBOUNDED → CLOSED.
 */
@Service
public class PurInboundPlanService {

    private final PurInboundPlanMapper planMapper;
    private final BillNoGenerator billNoGenerator;

    public PurInboundPlanService(PurInboundPlanMapper planMapper, BillNoGenerator billNoGenerator) {
        this.planMapper = planMapper;
        this.billNoGenerator = billNoGenerator;
    }

    @Transactional(readOnly = true)
    public PurInboundPlan findById(Long id) {
        if (id == null) return null;
        return planMapper.selectById(id);
    }

    @Transactional(readOnly = true)
    public PurInboundPlan findByPlanNo(String planNo) {
        if (StrUtil.isBlank(planNo)) return null;
        return planMapper.findByPlanNo(planNo);
    }

    @Transactional(readOnly = true)
    public IPage<PurInboundPlan> page(int pageNum, int pageSize, String planNo, Long supplierId, String planStatus) {
        Page<PurInboundPlan> p = new Page<>(pageNum, pageSize, true);
        return planMapper.selectPlanPage(p, planNo, supplierId, planStatus);
    }

    @Transactional(readOnly = true)
    public List<PurInboundPlan> listArrived() {
        return planMapper.listByStatus("ARRIVED");
    }

    @Transactional(rollbackFor = Exception.class)
    public Long add(PurInboundPlan p) {
        if (p == null) throw BizException.of("采购计划不能为空");
        if (StrUtil.isBlank(p.getPlanNo())) {
            // 计划单号: CGJH-yyyyMMdd-NNNN (BillNoGenerator 4 位自增)
            p.setPlanNo(billNoGenerator.generate("CGJH"));
        }
        if (StrUtil.isBlank(p.getPlanStatus())) p.setPlanStatus("PLANNED");
        if (StrUtil.isBlank(p.getSourceType())) p.setSourceType("MANUAL");
        planMapper.insert(p);
        return p.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(PurInboundPlan p) {
        if (p == null || p.getId() == null) throw BizException.of("plan id 必填");
        planMapper.updateById(p);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long id, String newStatus) {
        if (id == null || StrUtil.isBlank(newStatus)) return;
        PurInboundPlan p = planMapper.selectById(id);
        if (p == null) return;
        // 软更新: 用 LambdaUpdateWrapper 只改 status (避免全字段覆盖 NULL)
        planMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PurInboundPlan>()
                .eq(PurInboundPlan::getId, id)
                .set(PurInboundPlan::getPlanStatus, newStatus)
                .set(PurInboundPlan::getUpdateTime, java.time.LocalDateTime.now()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (id == null) return;
        planMapper.deleteById(id);
    }
}
