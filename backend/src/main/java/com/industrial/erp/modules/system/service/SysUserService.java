package com.industrial.erp.modules.system.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.modules.system.entity.SysUser;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import com.industrial.erp.security.PermissionService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SysUserService {

    private final SysUserMapper userMapper;
    private final PermissionService permService;
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    public SysUserService(SysUserMapper userMapper, PermissionService permService) {
        this.userMapper = userMapper;
        this.permService = permService;
    }

    public IPage<SysUser> page(Integer pageNum, Integer pageSize, String username, String realName, Long deptId) {
        permService.requirePerm("system:user:list");
        Page<SysUser> p = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysUser> w = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(username)) w.like(SysUser::getUsername, username);
        if (StrUtil.isNotBlank(realName)) w.like(SysUser::getRealName, realName);
        if (deptId != null) w.eq(SysUser::getDeptId, deptId);
        w.orderByDesc(SysUser::getId);
        return userMapper.selectPage(p, w);
    }

    public SysUser detail(Long id) {
        return userMapper.selectById(id);
    }

    public void add(SysUser user) {
        permService.requirePerm("system:user:add");
        if (userMapper.selectByUsername(user.getUsername()) != null) {
            throw new com.industrial.erp.exception.BizException("用户名已存在");
        }
        if (StrUtil.isBlank(user.getPassword())) {
            user.setPassword("123456");
        }
        user.setPassword(ENCODER.encode(user.getPassword()));
        if (user.getIsAdmin() == null) user.setIsAdmin(0);
        if (user.getStatus() == null) user.setStatus(1);
        userMapper.insert(user);
    }

    public void update(SysUser user) {
        permService.requirePerm("system:user:edit");
        // 只更新基本信息，密码由 resetPassword 单独处理
        SysUser u = new SysUser();
        u.setId(user.getId());
        if (StrUtil.isNotBlank(user.getNickname())) u.setNickname(user.getNickname());
        if (user.getPhone() != null) u.setPhone(user.getPhone());
        if (user.getEmail() != null) u.setEmail(user.getEmail());
        if (user.getSex() != null) u.setSex(user.getSex());
        if (user.getDeptId() != null) u.setDeptId(user.getDeptId());
        if (user.getStatus() != null) u.setStatus(user.getStatus());
        userMapper.updateById(u);
    }

    public void updatePassword(Long userId, String password) {
        permService.requirePerm("system:user:edit");
        SysUser u = new SysUser();
        u.setId(userId);
        u.setPassword(ENCODER.encode(password));
        userMapper.updateById(u);
    }

    public void delete(Long id) {
        permService.requirePerm("system:user:del");
        userMapper.deleteById(id);
    }

    public void resetPassword(Long id, String password) {
        permService.requirePerm("system:user:resetPwd");
        SysUser user = new SysUser();
        user.setId(id);
        user.setPassword(ENCODER.encode(password));
        userMapper.updateById(user);
    }

    public List<Long> getRoleIds(Long userId) {
        return userMapper.selectRoleIdsByUserId(userId);
    }

    public void assignRoles(Long userId, List<Long> roleIds) {
        permService.requirePerm("system:user:edit");
        userMapper.deleteUserRoles(userId);
        if (roleIds != null && !roleIds.isEmpty()) {
            userMapper.insertUserRolesBatch(userId, roleIds);
        }
    }
}
