package com.industrial.erp.modules.system.service;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.system.entity.SysMenu;
import com.industrial.erp.modules.system.mapper.SysMenuMapper;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SysMenuService {

    public SysMenuService(SysMenuMapper menuMapper, PermissionService permService, OperLogPublisher operLogPublisher) {
        this.menuMapper = menuMapper;
        this.permService = permService;
        this.operLogPublisher = operLogPublisher;
    }
    private final SysMenuMapper menuMapper;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;

    public List<SysMenu> listAll() {
        permService.requirePerm("system:menu:list");
        return menuMapper.selectList(null);
    }

    public List<SysMenu> listByUserId(Long userId) {
        return menuMapper.selectMenusByUserId(userId);
    }

    public void add(SysMenu m) { permService.requirePerm("system:menu:add"); menuMapper.insert(m); }
    public void update(SysMenu m) { permService.requirePerm("system:menu:edit"); menuMapper.updateById(m); }
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        permService.requirePerm("system:menu:delete");
        SysMenu m = menuMapper.selectById(id);
        if (m == null) throw BizException.of("菜单不存在或已删除");
        menuMapper.update(null, new LambdaUpdateWrapper<SysMenu>().eq(SysMenu::getId, id).set(SysMenu::getDeleted, 1));
        operLogPublisher.publishDeleteSnapshot("菜单管理", String.valueOf(id), m, null);
    }
}
