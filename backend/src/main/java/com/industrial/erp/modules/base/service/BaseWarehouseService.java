package com.industrial.erp.modules.base.service;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BaseWarehouseService {

    public BaseWarehouseService(BaseWarehouseMapper mapper, PermissionService permService, OperLogPublisher operLogPublisher) {
        this.mapper = mapper;
        this.permService = permService;
        this.operLogPublisher = operLogPublisher;
    }
    private final BaseWarehouseMapper mapper;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;

    public List<BaseWarehouse> list() { permService.requirePerm("base:warehouse:list"); return mapper.selectList(null); }
    public BaseWarehouse detail(Long id) { return mapper.selectById(id); }
    public void add(BaseWarehouse w) { permService.requirePerm("base:warehouse:add"); if (w.getStatus()==null) w.setStatus(1); mapper.insert(w); }
    public void update(BaseWarehouse w) { permService.requirePerm("base:warehouse:edit"); mapper.updateById(w); }
    public void delete(Long id) { permService.requirePerm("base:warehouse:delete"); BaseWarehouse w = mapper.selectById(id); if (w == null) throw BizException.of("仓库不存在或已删除"); mapper.update(null, new LambdaUpdateWrapper<BaseWarehouse>().eq(BaseWarehouse::getId, id).set(BaseWarehouse::getDeleted, 1)); operLogPublisher.publishDeleteSnapshot("仓库管理", String.valueOf(id), w, null); }
}
