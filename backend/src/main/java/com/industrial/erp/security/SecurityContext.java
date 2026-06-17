package com.industrial.erp.security;

import cn.dev33.satoken.stp.StpUtil;
import com.industrial.erp.common.Constants;

/**
 * 登录上下文工具
 */
public final class SecurityContext {
    private SecurityContext() {}

    public static boolean isLogin() {
        try {
            return StpUtil.isLogin();
        } catch (Exception e) {
            return false;
        }
    }

    public static Long getUserId() {
        if (!isLogin()) return null;
        Object id = StpUtil.getLoginIdDefaultNull();
        return id == null ? null : Long.valueOf(id.toString());
    }

    public static String getUsername() {
        if (!isLogin()) return null;
        Object o = StpUtil.getSession().get("username");
        return o == null ? null : o.toString();
    }

    public static Long getTenantId() {
        if (!isLogin()) return Constants.DEFAULT_TENANT;
        Object o = StpUtil.getSession().get(Constants.CURRENT_TENANT);
        return o == null ? Constants.DEFAULT_TENANT : Long.valueOf(o.toString());
    }

    public static boolean isSuperAdmin() {
        Long uid = getUserId();
        return uid != null && Constants.SUPER_ADMIN_ID.equals(uid);
    }
}
