package com.industrial.erp.security;

import cn.dev33.satoken.stp.StpUtil;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 权限/数据范围检查
 */
@Component
public class PermissionService {

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

    /** 数据范围: 1=全部 2=本部门及下级 3=本部门 4=本人 */
    public Integer getDataScope() {
        List<String> roles = StpUtil.getRoleList();
        if (roles == null || roles.isEmpty()) return 4;
        // 简单实现: 查第一个角色的 data_scope
        return 1;
    }
}
