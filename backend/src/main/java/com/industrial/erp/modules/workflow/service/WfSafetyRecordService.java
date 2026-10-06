package com.industrial.erp.modules.workflow.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.modules.workflow.entity.WfSafetyDetail;
import com.industrial.erp.modules.workflow.entity.WfSafetyRecord;
import com.industrial.erp.modules.workflow.mapper.WfSafetyDetailMapper;
import com.industrial.erp.modules.workflow.mapper.WfSafetyRecordMapper;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** v1.1.79 工作流: 安全检查记录服务 (简单台账 + 审核/反审核) */
@Service
public class WfSafetyRecordService {

    public WfSafetyRecordService(WfSafetyRecordMapper recordMapper, WfSafetyDetailMapper detailMapper,
                                BillNoGenerator billNoGenerator, PermissionService permService,
                                OperLogPublisher operLogPublisher) {
        this.recordMapper = recordMapper;
        this.detailMapper = detailMapper;
        this.billNoGenerator = billNoGenerator;
        this.permService = permService;
        this.operLogPublisher = operLogPublisher;
    }

    private final WfSafetyRecordMapper recordMapper;
    private final WfSafetyDetailMapper detailMapper;
    private final BillNoGenerator billNoGenerator;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;

    public IPage<WfSafetyRecord> page(Integer pageNum, Integer pageSize, String site, String billStatus, Boolean overdue) {
        permService.requirePerm("work:safety:list");
        Page<WfSafetyRecord> p = new Page<>(pageNum, pageSize);
        return recordMapper.selectPageList(p, site, billStatus, overdue);
    }

    public WfSafetyRecord detail(Long id) {
        WfSafetyRecord r = recordMapper.selectById(id);
        if (r != null) r.setDetails(detailMapper.selectByRecordId(id));
        return r;
    }

    @OperLog(module = "安全检查记录", businessType = "ADD", saveParam = true)
    @Transactional(rollbackFor = Exception.class)
    public void add(WfSafetyRecord r) {
        permService.requirePerm("work:safety:add");
        if (StrUtil.isBlank(r.getRecordNo())) r.setRecordNo(billNoGenerator.generate(Constants.BILL_SIN));
        if (StrUtil.isBlank(r.getBillStatus())) r.setBillStatus(Constants.STATUS_DRAFT);
        if (r.getCheckDate() == null) r.setCheckDate(LocalDate.now());
        if (r.getRiskCount() == null) r.setRiskCount(0);
        // 若带明细, 统计隐患行数回填 riskCount (明细里 riskDesc 非空的行)
        if (r.getDetails() != null) {
            int risk = 0;
            for (WfSafetyDetail d : r.getDetails()) {
                if (StrUtil.isNotBlank(d.getRiskDesc())) risk++;
            }
            r.setRiskCount(risk);
        }
        recordMapper.insert(r);
        insertDetails(r);
    }

    @OperLog(module = "安全检查记录", businessType = "EDIT", saveParam = true)
    @Transactional(rollbackFor = Exception.class)
    public void update(WfSafetyRecord r) {
        permService.requirePerm("work:safety:edit");
        WfSafetyRecord origin = recordMapper.selectById(r.getId());
        if (origin == null) throw BizException.of("记录不存在");
        if (!Constants.STATUS_DRAFT.equals(origin.getBillStatus())) throw BizException.of("只有草稿状态可修改");
        r.setRecordNo(origin.getRecordNo());
        r.setBillStatus(origin.getBillStatus());
        if (r.getDetails() != null) {
            int risk = 0;
            for (WfSafetyDetail d : r.getDetails()) {
                if (StrUtil.isNotBlank(d.getRiskDesc())) risk++;
            }
            r.setRiskCount(risk);
        }
        recordMapper.updateById(r);
        detailMapper.delete(new LambdaQueryWrapper<WfSafetyDetail>().eq(WfSafetyDetail::getRecordId, r.getId()));
        insertDetails(r);
    }

    private void insertDetails(WfSafetyRecord r) {
        if (r.getDetails() == null) return;
        for (WfSafetyDetail d : r.getDetails()) {
            d.setId(null);
            d.setRecordId(r.getId());
            detailMapper.insert(d);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        permService.requirePerm("work:safety:delete");
        WfSafetyRecord r = recordMapper.selectById(id);
        if (r == null) throw BizException.of("记录不存在");
        if (!Constants.STATUS_DRAFT.equals(r.getBillStatus())) throw BizException.of("只有草稿状态可删除");
        List<WfSafetyDetail> details = detailMapper.selectByRecordId(id);
        detailMapper.update(null, new LambdaUpdateWrapper<WfSafetyDetail>()
                .eq(WfSafetyDetail::getRecordId, id).set(WfSafetyDetail::getDeleted, 1));
        recordMapper.update(null, new LambdaUpdateWrapper<WfSafetyRecord>()
                .eq(WfSafetyRecord::getId, id).set(WfSafetyRecord::getDeleted, 1));
        operLogPublisher.publishDeleteSnapshot("安全检查记录", String.valueOf(id), r, details);
    }

    @OperLog(module = "安全检查记录", businessType = "CHECK", saveParam = true)
    @Transactional(rollbackFor = Exception.class)
    public void check(Long id) {
        permService.requirePerm("work:safety:check");
        WfSafetyRecord r = recordMapper.selectById(id);
        if (r == null) throw BizException.of("记录不存在");
        if (!Constants.STATUS_DRAFT.equals(r.getBillStatus())) throw BizException.of("只有草稿状态可审核");
        WfSafetyRecord upd = new WfSafetyRecord();
        upd.setId(id);
        upd.setBillStatus(Constants.STATUS_CHECKED);
        recordMapper.updateById(upd);
    }

    @OperLog(module = "安全检查记录", businessType = "UNCHECK", saveParam = true)
    @Transactional(rollbackFor = Exception.class)
    public void uncheck(Long id) {
        permService.requirePerm("work:safety:uncheck");
        WfSafetyRecord r = recordMapper.selectById(id);
        if (r == null) throw BizException.of("记录不存在");
        if (!Constants.STATUS_CHECKED.equals(r.getBillStatus())) throw BizException.of("只有已审核状态可反审核");
        WfSafetyRecord upd = new WfSafetyRecord();
        upd.setId(id);
        upd.setBillStatus(Constants.STATUS_DRAFT);
        recordMapper.updateById(upd);
    }
}
