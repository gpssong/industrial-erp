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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** v1.0.10+: 查询角色按客户端类型分组已分配的菜单 */
    public Map<String, List<SysMenu>> getMenuByClientType(Long roleId) {
        Map<String, List<SysMenu>> result = new HashMap<>();
        result.put("PC", menuMapper.selectMenusByRoleIdAndClient(roleId, "PC"));
        result.put("APP", menuMapper.selectMenusByRoleIdAndClient(roleId, "APP"));
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public void grantMenus(Long roleId, List<Long> menuIds) {
        permService.requirePerm("system:role:edit");
        roleMapper.deleteRoleMenus(roleId);
        if (menuIds != null && !menuIds.isEmpty()) {
            // 去重: 防止前端传来重复 menuId 导致 DuplicateKey
            roleMapper.insertRoleMenuBatch(roleId, menuIds.stream().distinct().toList());
        }
    }

    /** v1.0.10+: 按客户端类型分配菜单权限
     *  entries: [{menuId: "123", clientType: "PC"}]
     *  <p>注意: 多个白名单项可能映射到同一 sys_menu.id (例如"外勤盘点"和"生产加工单新增"
     *  共用 production:order:list), 提交时必须去重避免 DuplicateKeyException
     *  <p>v1.1.8+: APP/PC Tab 提交时, 同时清理同 menu_id 的 BOTH 记录 — 否则下次再进,
     *  旧 BOTH 记录会让菜单仍被识别为"已分配" (menusByClient 用 IN('BOTH', clientType) 过滤)
     */
    @Transactional(rollbackFor = Exception.class)
    public void grantMenusByClient(Long roleId, String clientType, List<Long> menuIds) {
        permService.requirePerm("system:role:edit");
        if ("BOTH".equals(clientType)) {
            roleMapper.deleteRoleMenus(roleId);
        } else {
            // v1.1.8+: APP/PC Tab 提交时, 迁移旧 BOTH 记录
            // 规则 (见 plan):
            //   - 角色 BOTH 记录中 menu_id 不在本次 menuIds 的:
            //     * 若该 role 已有 menu_id + 对端 client_type 独立记录 → 删 BOTH (避免重复)
            //     * 若只有 BOTH 记录 → 升级为对端 client_type (otherCt), 让对端仍可用
            //   实现: 在 Java 层分两步走 (避开 MySQL "can't UPDATE self SELECT" 限制)
            String otherCt = "APP".equals(clientType) ? "PC" : "APP";
            List<Long> menuIdList = menuIds == null ? java.util.Collections.emptyList() : menuIds;
            // 1. 查询需要处理的 BOTH 记录 (menu_id 不在 menuIds)
            java.util.List<Long> bothMenuIds = roleMapper.selectBOTHMenuIdsNotIn(roleId, menuIdList);
            if (!bothMenuIds.isEmpty()) {
                // 2. 对每个 menu_id:
                //    - 查对端 client_type 是否有独立记录
                for (Long mid : bothMenuIds) {
                    int otherCtCount = roleMapper.countByRoleAndMenuAndClient(roleId, mid, otherCt);
                    if (otherCtCount > 0) {
                        // 对端已有, 删 BOTH
                        roleMapper.deleteRoleMenusByClientAndMenuIds(roleId, List.of("BOTH"), List.of(mid));
                    } else {
                        // v1.1.11+ 修: 对端无时, 直接删 BOTH 而不是升级到 otherCt
                        // 原"升级 BOTH → otherCt"会把 menu 误标到对端, 导致:
                        //   - 用户在 PC 取消勾选 → menusByClient(PC) 仍因 BOTH 而含该 perm
                        //   - 用户在 APP 取消勾选 → 同样问题
                        // BOTH 是历史遗留, 当前端按 PC/APP 分轨提交, 应直接清理, 不再升级
                        roleMapper.deleteRoleMenusByClientAndMenuIds(roleId, List.of("BOTH"), List.of(mid));
                    }
                }
            }
            // 3. 删本次 client_type 的纯 APP/PC 记录
            roleMapper.deleteRoleMenusByClient(roleId, List.of(clientType));
        }
        if (menuIds != null && !menuIds.isEmpty()) {
            // 去重: 多个白名单项可能映射到同一 sys_menu.id
            // v1.1.12+ 修: 过滤掉 menu_type='M' (目录节点), 只保留按钮 (B) 和顶级页面菜单 (M 但有 perms)
            // 原 bug: el-tree 父子联动下, 前端 getHalfCheckedKeys 返回父目录, 把 M 类型菜单写入 sys_role_menu,
            // 下次打开 setCheckedKeys 时父目录自动 checked → 联动子按钮全部 checked → 用户看到"取消后又勾上"
            List<Long> filteredMenuIds = menuIds.stream().distinct()
                .filter(mid -> isGrantableMenu(mid))
                .toList();
            List<Long> uniqueMenuIds = filteredMenuIds;
            if (!uniqueMenuIds.isEmpty()) {
                Map<String, String> entry = new HashMap<>();
                entry.put("clientType", clientType);
                List<Map<String, String>> entries = uniqueMenuIds.stream()
                        .map(mid -> { Map<String, String> m = new HashMap<>(); m.put("menuId", mid.toString()); m.putAll(entry); return m; })
                        .toList();
                roleMapper.insertRoleMenuBatchByClient(roleId, entries);
            }
        }
    }

    /**
     * v1.1.12+: 判断 menu 是否可作为权限项授权.
     * 只允许 menu_type='B' (按钮) 和有 perms 的菜单 (实际功能项).
     * 过滤掉纯 M 类型目录节点 — 它们是 el-tree 父子联动产生的中间节点, 写入会导致下次打开时整父联动.
     */
    private boolean isGrantableMenu(Long menuId) {
        SysMenu m = menuMapper.selectById(menuId);
        if (m == null) return false;
        // 按钮 (B) 永远可授权
        if ("B".equals(m.getMenuType())) return true;
        // 菜单节点 (M) 只有带 perms 才算功能项 (例如 工作台/报表/工作台菜单)
        if ("M".equals(m.getMenuType())) {
            return m.getPerms() != null && !m.getPerms().trim().isEmpty();
        }
        // 其它类型 (C 目录) 一律过滤
        return false;
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
