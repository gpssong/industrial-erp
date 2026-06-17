package com.industrial.erp.modules.base.service;

import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BaseWarehouseService {

    public BaseWarehouseService(BaseWarehouseMapper mapper, PermissionService permService) {
        this.mapper = mapper;
        this.permService = permService;
    }
    private final BaseWarehouseMapper mapper;
    private final PermissionService permService;

    public List<BaseWarehouse> list() { permService.requirePerm("base:warehouse:list"); return mapper.selectList(null); }
    public BaseWarehouse detail(Long id) { return mapper.selectById(id); }
    public void add(BaseWarehouse w) { permService.requirePerm("base:warehouse:add"); if (w.getStatus()==null) w.setStatus(1); mapper.insert(w); }
    public void update(BaseWarehouse w) { permService.requirePerm("base:warehouse:edit"); mapper.updateById(w); }
    public void delete(Long id) { permService.requirePerm("base:warehouse:delete"); mapper.deleteById(id); }
}
