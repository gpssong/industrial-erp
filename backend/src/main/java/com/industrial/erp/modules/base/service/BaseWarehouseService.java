package com.industrial.erp.modules.base.service;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.system.annotation.OperLog;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * v1.1.163 — 与服务层之外的 controller 注解同口径放宽: 持有采购入库列表权限的 App 角色
     * (FINANCE / WAREHOUSE_MGR / zdg) 需要读仓库名录给入库单选仓, 而 base:warehouse:list
     * 不在 PC「App 权限树」白名单里, 界面上授不出去 → 只认它会 403.
     * 这里必须一起放宽, 否则 controller 的 OR 会被本行 requirePerm 二次拦掉.
     */
    public List<BaseWarehouse> list() {
        permService.requireAnyPerm("base:warehouse:list", "purchase:receipt:list");
        return mapper.selectList(null);
    }
    public BaseWarehouse detail(Long id) { return mapper.selectById(id); }
    @OperLog(module="仓库管理", businessType="ADD", saveParam=true)
    @Transactional(rollbackFor = Exception.class)
    public void add(BaseWarehouse w) { permService.requirePerm("base:warehouse:add"); if (w.getStatus()==null) w.setStatus(1); mapper.insert(w); }
    @OperLog(module="仓库管理", businessType="EDIT", saveParam=true)
    @Transactional(rollbackFor = Exception.class)
    public void update(BaseWarehouse w) { permService.requirePerm("base:warehouse:edit"); mapper.updateById(w); }
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) { permService.requirePerm("base:warehouse:delete"); BaseWarehouse w = mapper.selectById(id); if (w == null) throw BizException.of("仓库不存在或已删除"); mapper.update(null, new LambdaUpdateWrapper<BaseWarehouse>().eq(BaseWarehouse::getId, id).set(BaseWarehouse::getDeleted, 1)); operLogPublisher.publishDeleteSnapshot("仓库管理", String.valueOf(id), w, null); }
}
