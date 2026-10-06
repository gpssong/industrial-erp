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
import com.industrial.erp.modules.workflow.entity.WfMaintainDetail;
import com.industrial.erp.modules.workflow.entity.WfMaintainRecord;
import com.industrial.erp.modules.workflow.mapper.WfMaintainDetailMapper;
import com.industrial.erp.modules.workflow.mapper.WfMaintainRecordMapper;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.utils.BillNoGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** v1.1.79 工作流: 设备维保记录服务 (简单台账 + 审核/反审核) */
@Service
public class WfMaintainRecordService {

    public WfMaintainRecordService(WfMaintainRecordMapper recordMapper, WfMaintainDetailMapper detailMapper,
                                   BillNoGenerator billNoGenerator, PermissionService permService,
                                   OperLogPublisher operLogPublisher) {
        this.recordMapper = recordMapper;
        this.detailMapper = detailMapper;
        this.billNoGenerator = billNoGenerator;
        this.permService = permService;
        this.operLogPublisher = operLogPublisher;
    }

    private final WfMaintainRecordMapper recordMapper;
    private final WfMaintainDetailMapper detailMapper;
    private final BillNoGenerator billNoGenerator;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;

    public IPage<WfMaintainRecord> page(Integer pageNum, Integer pageSize, String deviceName, String billStatus, Boolean overdue) {
        permService.requirePerm("work:maintain:list");
        Page<WfMaintainRecord> p = new Page<>(pageNum, pageSize);
        return recordMapper.selectPageList(p, deviceName, billStatus, overdue);
    }

    public WfMaintainRecord detail(Long id) {
        WfMaintainRecord r = recordMapper.selectById(id);
        if (r != null) r.setDetails(detailMapper.selectByRecordId(id));
        return r;
    }

    @OperLog(module = "设备维保记录", businessType = "ADD", saveParam = true)
    @Transactional(rollbackFor = Exception.class)
    public void add(WfMaintainRecord r) {
        permService.requirePerm("work:maintain:add");
        if (StrUtil.isBlank(r.getRecordNo())) r.setRecordNo(billNoGenerator.generate(Constants.BILL_WM));
        if (StrUtil.isBlank(r.getBillStatus())) r.setBillStatus(Constants.STATUS_DRAFT);
        if (r.getMaintDate() == null) r.setMaintDate(LocalDate.now());
        recordMapper.insert(r);
        insertDetails(r);
    }

    @OperLog(module = "设备维保记录", businessType = "EDIT", saveParam = true)
    @Transactional(rollbackFor = Exception.class)
    public void update(WfMaintainRecord r) {
        permService.requirePerm("work:maintain:edit");
        WfMaintainRecord origin = recordMapper.selectById(r.getId());
        if (origin == null) throw BizException.of("记录不存在");
        if (!Constants.STATUS_DRAFT.equals(origin.getBillStatus())) throw BizException.of("只有草稿状态可修改");
        r.setRecordNo(origin.getRecordNo());
        r.setBillStatus(origin.getBillStatus());
        recordMapper.updateById(r);
        detailMapper.delete(new LambdaQueryWrapper<WfMaintainDetail>().eq(WfMaintainDetail::getRecordId, r.getId()));
        insertDetails(r);
    }

    private void insertDetails(WfMaintainRecord r) {
        if (r.getDetails() == null) return;
        for (WfMaintainDetail d : r.getDetails()) {
            d.setId(null);
            d.setRecordId(r.getId());
            detailMapper.insert(d);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        permService.requirePerm("work:maintain:delete");
        WfMaintainRecord r = recordMapper.selectById(id);
        if (r == null) throw BizException.of("记录不存在");
        if (!Constants.STATUS_DRAFT.equals(r.getBillStatus())) throw BizException.of("只有草稿状态可删除");
        List<WfMaintainDetail> details = detailMapper.selectByRecordId(id);
        detailMapper.update(null, new LambdaUpdateWrapper<WfMaintainDetail>()
                .eq(WfMaintainDetail::getRecordId, id).set(WfMaintainDetail::getDeleted, 1));
        recordMapper.update(null, new LambdaUpdateWrapper<WfMaintainRecord>()
                .eq(WfMaintainRecord::getId, id).set(WfMaintainRecord::getDeleted, 1));
        operLogPublisher.publishDeleteSnapshot("设备维保记录", String.valueOf(id), r, details);
    }

    @OperLog(module = "设备维保记录", businessType = "CHECK", saveParam = true)
    @Transactional(rollbackFor = Exception.class)
    public void check(Long id) {
        permService.requirePerm("work:maintain:check");
        WfMaintainRecord r = recordMapper.selectById(id);
        if (r == null) throw BizException.of("记录不存在");
        if (!Constants.STATUS_DRAFT.equals(r.getBillStatus())) throw BizException.of("只有草稿状态可审核");
        WfMaintainRecord upd = new WfMaintainRecord();
        upd.setId(id);
        upd.setBillStatus(Constants.STATUS_CHECKED);
        recordMapper.updateById(upd);
    }

    @OperLog(module = "设备维保记录", businessType = "UNCHECK", saveParam = true)
    @Transactional(rollbackFor = Exception.class)
    public void uncheck(Long id) {
        permService.requirePerm("work:maintain:uncheck");
        WfMaintainRecord r = recordMapper.selectById(id);
        if (r == null) throw BizException.of("记录不存在");
        if (!Constants.STATUS_CHECKED.equals(r.getBillStatus())) throw BizException.of("只有已审核状态可反审核");
        WfMaintainRecord upd = new WfMaintainRecord();
        upd.setId(id);
        upd.setBillStatus(Constants.STATUS_DRAFT);
        recordMapper.updateById(upd);
    }
}
