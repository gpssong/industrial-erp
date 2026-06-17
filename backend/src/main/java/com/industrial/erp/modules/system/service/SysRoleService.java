package com.industrial.erp.modules.system.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.modules.system.entity.SysMenu;
import com.industrial.erp.modules.system.entity.SysRole;
import com.industrial.erp.modules.system.mapper.SysMenuMapper;
import com.industrial.erp.modules.system.mapper.SysRoleMapper;
import com.industrial.erp.security.PermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SysRoleService {

    public SysRoleService(SysRoleMapper roleMapper, SysMenuMapper menuMapper, PermissionService permService) {
        this.roleMapper = roleMapper;
        this.menuMapper = menuMapper;
        this.permService = permService;
    }
    private final SysRoleMapper roleMapper;
    private final SysMenuMapper menuMapper;
    private final PermissionService permService;

    public IPage<SysRole> page(Integer pageNum, Integer pageSize, String roleName) {
        permService.requirePerm("system:role:list");
        Page<SysRole> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysRole> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(roleName)) w.like(SysRole::getRoleName, roleName);
        w.orderByDesc(SysRole::getId);
        return roleMapper.selectPage(p, w);
    }

    public SysRole detail(Long id) { return roleMapper.selectById(id); }

    public void add(SysRole r) {
        permService.requirePerm("system:role:add");
        if (r.getStatus() == null) r.setStatus(1);
        if (r.getSortNo() == null) r.setSortNo(0);
        roleMapper.insert(r);
    }

    public void update(SysRole r) {
        permService.requirePerm("system:role:edit");
        roleMapper.updateById(r);
    }

    public void delete(Long id) {
        permService.requirePerm("system:role:delete");
        roleMapper.deleteById(id);
    }

    public List<SysMenu> getMenusByRoleId(Long roleId) {
        return menuMapper.selectMenusByRoleId(roleId);
    }

    @Transactional
    public void grantMenus(Long roleId, List<Long> menuIds) {
        permService.requirePerm("system:role:edit");
        roleMapper.deleteRoleMenus(roleId);
        if (menuIds != null && !menuIds.isEmpty()) {
            roleMapper.insertRoleMenuBatch(roleId, menuIds);
        }
    }

    public List<Long> getUserIdsByRoleId(Long roleId) {
        return roleMapper.selectUserIdsByRoleId(roleId);
    }

    @Transactional
    public void assignUsers(Long roleId, List<Long> userIds) {
        permService.requirePerm("system:role:edit");
        roleMapper.deleteUserRoles(roleId);
        if (userIds != null && !userIds.isEmpty()) {
            roleMapper.insertUserRoleBatch(roleId, userIds);
        }
    }
}
