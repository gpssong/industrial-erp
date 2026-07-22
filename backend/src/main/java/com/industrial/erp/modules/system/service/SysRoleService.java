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
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SysRoleService {

    public SysRoleService(SysRoleMapper roleMapper, SysMenuMapper menuMapper, PermissionService permService, OperLogPublisher operLogPublisher) {
        this.roleMapper = roleMapper;
        this.menuMapper = menuMapper;
        this.permService = permService;
        this.operLogPublisher = operLogPublisher;
    }
    private final SysRoleMapper roleMapper;
    private final SysMenuMapper menuMapper;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;

    public IPage<SysRole> page(Integer pageNum, Integer pageSize, String roleName) {
        permService.requirePerm("system:role:list");
        Page<SysRole> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysRole> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(roleName)) w.like(SysRole::getRoleName, roleName);
        w.orderByDesc(SysRole::getId);
        return roleMapper.selectPage(p, w);
    }

    public SysRole detail(Long id) { return roleMapper.selectById(id); }

    @Transactional(rollbackFor = Exception.class)
    public void add(SysRole r) {
        permService.requirePerm("system:role:add");
        if (r.getStatus() == null) r.setStatus(1);
        if (r.getSortNo() == null) r.setSortNo(0);
        roleMapper.insert(r);
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(SysRole r) {
        permService.requirePerm("system:role:edit");
        roleMapper.updateById(r);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        permService.requirePerm("system:role:delete");
        SysRole r = roleMapper.selectById(id);
        if (r == null) throw new com.industrial.erp.exception.BizException("角色不存在或已删除");
        // 清理关联表 (角色-菜单、用户-角色) — 关联关系是物理实体
        roleMapper.deleteRoleMenus(id);
        roleMapper.deleteUserRoles(id);
        // 软删除主表
        roleMapper.update(null, new LambdaUpdateWrapper<SysRole>()
                .eq(SysRole::getId, id).set(SysRole::getDeleted, 1));
        operLogPublisher.publishDeleteSnapshot("角色管理", String.valueOf(id), r, null);
    }

    public List<SysMenu> getMenusByRoleId(Long roleId) {
        return menuMapper.selectMenusByRoleId(roleId);
    }

    @Transactional(rollbackFor = Exception.class)
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

    @Transactional(rollbackFor = Exception.class)
    public void assignUsers(Long roleId, List<Long> userIds) {
        permService.requirePerm("system:role:edit");
        roleMapper.deleteUserRoles(roleId);
        if (userIds != null && !userIds.isEmpty()) {
            roleMapper.insertUserRoleBatch(roleId, userIds);
        }
    }
}
