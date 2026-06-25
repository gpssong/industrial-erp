package com.industrial.erp.security;

import cn.dev33.satoken.stp.StpInterface;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 权限/角色数据源。
 * StpUtil.hasPermission / hasRole 调用此接口查询当前用户的权限与角色。
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    private final SysUserMapper userMapper;

    public StpInterfaceImpl(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Long userId = Long.valueOf(loginId.toString());
        List<String> perms = userMapper.selectPermsByUserId(userId);
        return perms == null ? Collections.emptyList() : perms;
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = Long.valueOf(loginId.toString());
        List<String> roles = userMapper.selectRoleCodesByUserId(userId);
        return roles == null ? Collections.emptyList() : roles;
    }
}
