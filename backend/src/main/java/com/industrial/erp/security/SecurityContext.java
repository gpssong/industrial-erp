package com.industrial.erp.security;

import cn.dev33.satoken.stp.StpUtil;
import com.industrial.erp.common.Constants;

/**
 * 登录上下文工具
 */
public final class SecurityContext {
    private SecurityContext() {}

    /** 仅供单元测试使用的 ThreadLocal: 在没有 Sa-Token 上下文时返回指定 userId */
    private static final ThreadLocal<Long> TEST_USER_ID = new ThreadLocal<>();

    /** 测试用: 设置当前线程的 userId (绕过 Sa-Token) */
    public static void setCurrentUserIdForTest(Long userId) {
        TEST_USER_ID.set(userId);
    }

    /** 测试用: 清除 ThreadLocal, 避免污染其他用例 */
    public static void clearForTest() {
        TEST_USER_ID.remove();
    }

    public static boolean isLogin() {
        Long testUid = TEST_USER_ID.get();
        if (testUid != null) return true;
        try {
            return StpUtil.isLogin();
        } catch (Exception e) {
            return false;
        }
    }

    public static Long getUserId() {
        Long testUid = TEST_USER_ID.get();
        if (testUid != null) return testUid;
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
        if (uid == null) return false;
        // 同时支持 ID=1 和 is_admin=1 的用户
        if (Constants.SUPER_ADMIN_ID.equals(uid)) return true;
        // is_admin 字段从 Session 中获取（登录时已存入）
        // Redis 反序列化可能变成 String/Long，用 "1" 比较更安全
        try {
            Object isAdmin = StpUtil.getSession().get("isAdmin");
            return isAdmin != null && "1".equals(isAdmin.toString());
        } catch (Exception e) {
            return false;
        }
    }
}
