package com.industrial.erp.security;

import cn.dev33.satoken.stp.StpUtil;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.system.mapper.SysRoleMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 权限 / 数据范围检查
 */
@Component
public class PermissionService {

    public static final int SCOPE_ALL = 1;       // 全部数据
    public static final int SCOPE_DEPT_SUB = 2;  // 本部门及下级
    public static final int SCOPE_DEPT = 3;      // 本部门
    public static final int SCOPE_SELF = 4;      // 仅本人

    /** Session 中缓存的 data_scope key */
    public static final String SESSION_DATA_SCOPE = "dataScope";

    private final SysRoleMapper roleMapper;

    public PermissionService(SysRoleMapper roleMapper) {
        this.roleMapper = roleMapper;
    }

    public Long getCurrentUserId() {
        if (!SecurityContext.isLogin()) {
            throw BizException.of(401, "未登录");
        }
        return SecurityContext.getUserId();
    }

    public boolean hasPerm(String perm) {
        if (SecurityContext.isSuperAdmin()) return true;
        return StpUtil.hasPermission(perm);
    }

    public void requirePerm(String perm) {
        if (!hasPerm(perm)) {
            throw BizException.of(403, "无权限: " + perm);
        }
    }

    public void requireSuperAdmin() {
        if (!SecurityContext.isSuperAdmin()) {
            throw BizException.of(403, "只有超级管理员才能执行此操作");
        }
    }

    public boolean hasRole(String role) {
        if (SecurityContext.isSuperAdmin()) return true;
        return StpUtil.hasRole(role);
    }

    /**
     * 数据范围: 1=全部 2=本部门及下级 3=本部门 4=本人。
     *
     * <p>优先级: 多个角色取权限最大的 (即数字最小的), 超级管理员直接 = 1。
     * 结果会缓存到 Sa-Token Session 中 (登录时写入), 避免每请求查库。
     */
    public Integer getDataScope() {
        if (SecurityContext.isSuperAdmin()) return SCOPE_ALL;
        if (!SecurityContext.isLogin()) return SCOPE_SELF;

        Object cached = StpUtil.getSession().get(SESSION_DATA_SCOPE);
        if (cached instanceof Integer) return (Integer) cached;

        // 缓存未命中 (例如 session 过期重建), 回源查库
        Integer computed = computeDataScope(SecurityContext.getUserId());
        if (computed != null) {
            StpUtil.getSession().set(SESSION_DATA_SCOPE, computed);
        }
        return computed == null ? SCOPE_SELF : computed;
    }

    private Integer computeDataScope(Long userId) {
        if (userId == null) return SCOPE_SELF;
        List<Integer> scopes = roleMapper.selectDataScopesByUserId(userId);
        if (scopes == null || scopes.isEmpty()) return SCOPE_SELF;
        return scopes.stream().filter(s -> s != null).min(Integer::compareTo).orElse(SCOPE_SELF);
    }
}
