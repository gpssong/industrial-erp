package com.industrial.erp.modules.base.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseSupplier;
import com.industrial.erp.modules.base.mapper.BaseSupplierMapper;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;

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
    public void add(BaseSupplier s) { permService.requirePerm("base:supplier:add"); if (s.getStatus()==null) s.setStatus(1); mapper.insert(s); }
    public void update(BaseSupplier s) { permService.requirePerm("base:supplier:edit"); mapper.updateById(s); }
    public void delete(Long id) { permService.requirePerm("base:supplier:delete"); BaseSupplier s = mapper.selectById(id); if (s == null) throw BizException.of("供应商不存在或已删除"); mapper.update(null, new LambdaUpdateWrapper<BaseSupplier>().eq(BaseSupplier::getId, id).set(BaseSupplier::getDeleted, 1)); operLogPublisher.publishDeleteSnapshot("供应商管理", String.valueOf(id), s, null); }
}
