package com.industrial.erp.modules.system.service;

import com.industrial.erp.modules.system.entity.SysMenu;
import com.industrial.erp.modules.system.mapper.SysMenuMapper;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SysMenuService {

    public SysMenuService(SysMenuMapper menuMapper, PermissionService permService) {
        this.menuMapper = menuMapper;
        this.permService = permService;
    }
    private final SysMenuMapper menuMapper;
    private final PermissionService permService;

    public List<SysMenu> listAll() {
        permService.requirePerm("system:menu:list");
        return menuMapper.selectList(null);
    }

    public List<SysMenu> listByUserId(Long userId) {
        return menuMapper.selectMenusByUserId(userId);
    }

    public void add(SysMenu m) { permService.requirePerm("system:menu:add"); menuMapper.insert(m); }
    public void update(SysMenu m) { permService.requirePerm("system:menu:edit"); menuMapper.updateById(m); }
    public void delete(Long id) { permService.requirePerm("system:menu:delete"); menuMapper.deleteById(id); }
}
