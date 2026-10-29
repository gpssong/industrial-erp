package com.industrial.erp.modules.base.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseSupplier;
import com.industrial.erp.modules.base.mapper.BaseSupplierMapper;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class BaseSupplierService {

    public BaseSupplierService(BaseSupplierMapper mapper, PermissionService permService, OperLogPublisher operLogPublisher) {
        this.mapper = mapper;
        this.permService = permService;
        this.operLogPublisher = operLogPublisher;
    }
    private final BaseSupplierMapper mapper;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;

    public IPage<BaseSupplier> page(Integer pageNum, Integer pageSize, String keyword) {
        permService.requirePerm("base:supplier:list");
        Page<BaseSupplier> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<BaseSupplier> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(keyword)) w.and(q -> q.like(BaseSupplier::getSupplierCode, keyword)
                .or().like(BaseSupplier::getSupplierName, keyword).or().like(BaseSupplier::getPhone, keyword));
        w.orderByDesc(BaseSupplier::getId);
        return mapper.selectPage(p, w);
    }

    public List<BaseSupplier> list() {
        return mapper.selectList(new LambdaQueryWrapper<BaseSupplier>().eq(BaseSupplier::getStatus, 1).orderByAsc(BaseSupplier::getSupplierName));
    }

    public BaseSupplier detail(Long id) { return mapper.selectById(id); }
    @OperLog(module="供应商管理", businessType="ADD", saveParam=true)
    @Transactional(rollbackFor = Exception.class)
    public void add(BaseSupplier s) { permService.requirePerm("base:supplier:add"); if (s.getStatus()==null) s.setStatus(1); mapper.insert(s); }
    @OperLog(module="供应商管理", businessType="EDIT", saveParam=true)
    @Transactional(rollbackFor = Exception.class)
    public void update(BaseSupplier s) {
        permService.requirePerm("base:supplier:edit");
        BaseSupplier origin = s.getId() == null ? null : mapper.selectById(s.getId());
        if (origin == null) throw BizException.of("供应商不存在或已删除");
        // v1.1.130 P1: 准入状态变更必须走 XK 合格供应商台账 (暂停/恢复), 否则丢失原因/操作人/证据
        if (s.getStatus() != null && !s.getStatus().equals(origin.getStatus())) {
            throw BizException.of("准入状态不能直接修改, 请到「XK 合规 → 合格供应商 → 暂停与恢复供货」操作, 以保留台账与审计记录");
        }
        mapper.updateById(s);
    }
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) { permService.requirePerm("base:supplier:delete"); BaseSupplier s = mapper.selectById(id); if (s == null) throw BizException.of("供应商不存在或已删除"); mapper.update(null, new LambdaUpdateWrapper<BaseSupplier>().eq(BaseSupplier::getId, id).set(BaseSupplier::getDeleted, 1)); operLogPublisher.publishDeleteSnapshot("供应商管理", String.valueOf(id), s, null); }
}
