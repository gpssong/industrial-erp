package com.industrial.erp.modules.system.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.modules.system.entity.SysUser;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import com.industrial.erp.modules.system.aspect.OperLogPublisher;
import com.industrial.erp.security.PermissionService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SysUserService {

    private static final Logger log = LoggerFactory.getLogger(SysUserService.class);

    private final SysUserMapper userMapper;
    private final PermissionService permService;
    private final OperLogPublisher operLogPublisher;
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final java.util.concurrent.ThreadLocalRandom RAND = java.util.concurrent.ThreadLocalRandom.current();
    private static final String ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
    private static final String DIGIT = "23456789";

    /**
     * 密码复杂度校验 (S1 安全红线): 至少 8 位, 且同时含字母与数字.
     * 任何设置/重置密码入口必须调用, 阻断 "a" / "1" 这类可爆破密码.
     */
    private void assertPasswordComplexity(String pwd, String action) {
        if (StrUtil.isBlank(pwd)) {
            throw new com.industrial.erp.exception.BizException(action + "密码不能为空");
        }
        if (pwd.length() < 8) {
            throw new com.industrial.erp.exception.BizException(action + "密码至少 8 位");
        }
        boolean hasAlpha = pwd.chars().anyMatch(Character::isLetter);
        boolean hasDigit = pwd.chars().anyMatch(Character::isDigit);
        if (!hasAlpha || !hasDigit) {
            throw new com.industrial.erp.exception.BizException(action + "密码需同时包含字母和数字");
        }
    }

    /** 生成 12 位随机强密码 (16 位字母数字池随机取, 防弱口令), 供新建账号兜底. */
    private String randomStrongPassword() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            String pool = (i % 3 == 0) ? DIGIT : ALPHA;
            sb.append(pool.charAt(RAND.nextInt(pool.length())));
        }
        return sb.toString();
    }

    public SysUserService(SysUserMapper userMapper, PermissionService permService, OperLogPublisher operLogPublisher) {
        this.userMapper = userMapper;
        this.permService = permService;
        this.operLogPublisher = operLogPublisher;
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

    @Transactional(rollbackFor = Exception.class)
    public void add(SysUser user) {
        permService.requirePerm("system:user:add");
        if (userMapper.selectByUsername(user.getUsername()) != null) {
            throw new com.industrial.erp.exception.BizException("用户名已存在");
        }
        if (StrUtil.isBlank(user.getPassword())) {
            // S1 安全红线: 空密码不再兜底 "123456", 改 12 位随机强密码.
            // 真实生效后用户必须首次登录走 AuthService.setPassword 修改.
            String random = randomStrongPassword();
            user.setPassword(random);
            log.warn("[SysUser#add] 用户 {} 密码为空, 自动生成随机强密码 (首次登录后需立即修改)", user.getUsername());
        }
        assertPasswordComplexity(user.getPassword(), "新建账号");
        user.setPassword(ENCODER.encode(user.getPassword()));
        if (user.getIsAdmin() == null) user.setIsAdmin(0);
        if (user.getStatus() == null) user.setStatus(1);
        userMapper.insert(user);
    }

    @Transactional(rollbackFor = Exception.class)
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

    /**
     * 修改指定用户的密码 (管理端). 鉴权规则:
     * <ul>
     *   <li>本人改自己: 必须传 oldPassword 校验 (防会话劫持)</li>
     *   <li>超管改他人: 不需要 oldPassword (这是超管的核心职责, 用户请求 v1.1.10+ 调整)</li>
     *   <li>非超管改他人: 拒绝</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public void updatePassword(Long userId, String newPassword, String oldPassword) {
        permService.requirePerm("system:user:edit");
        Long currentUid = com.industrial.erp.security.SecurityContext.getUserId();
        boolean isSuperAdmin = com.industrial.erp.security.SecurityContext.isSuperAdmin();
        boolean isSelf = currentUid != null && currentUid.equals(userId);
        if (!isSelf && !isSuperAdmin) {
            throw new com.industrial.erp.exception.BizException(
                    403, "仅本人或超级管理员可重置其他用户密码");
        }
        // 本人改自己: 必须校验旧密码
        if (isSelf) {
            if (StrUtil.isBlank(oldPassword)) {
                throw new com.industrial.erp.exception.BizException(
                        400, "修改本人密码需传入 oldPassword 校验");
            }
            SysUser self = userMapper.selectById(userId);
            if (self == null) throw new com.industrial.erp.exception.BizException("用户不存在");
            if (!ENCODER.matches(oldPassword, self.getPassword())) {
                throw new com.industrial.erp.exception.BizException("旧密码校验失败");
            }
        }
        // 超管改他人: 直接跳过 oldPassword 校验 (v1.1.10+ 用户要求)
        assertPasswordComplexity(newPassword, isSelf ? "本人改密" : "管理员重置");
        SysUser u = new SysUser();
        u.setId(userId);
        u.setPassword(ENCODER.encode(newPassword));
        userMapper.updateById(u);
    }

    /**
     * 用户改自己的密码 — 校验旧密码, 不需任何权限.
     * 不知道旧密码的用户必须联系超管重置 (AuthService.setPassword).
     */
    @Transactional(rollbackFor = Exception.class)
    public void changeOwnPassword(String oldPwd, String newPwd) {
        if (StrUtil.isBlank(oldPwd) || StrUtil.isBlank(newPwd)) {
            throw new com.industrial.erp.exception.BizException("原密码和新密码均不能为空");
        }
        if (oldPwd.equals(newPwd)) {
            throw new com.industrial.erp.exception.BizException("新密码不能与原密码相同");
        }
        assertPasswordComplexity(newPwd, "本人改密");
        Long uid = com.industrial.erp.security.SecurityContext.getUserId();
        if (uid == null) throw new com.industrial.erp.exception.BizException(401, "未登录");
        SysUser u = userMapper.selectById(uid);
        if (u == null) throw new com.industrial.erp.exception.BizException("用户不存在");
        if (!ENCODER.matches(oldPwd, u.getPassword())) {
            throw new com.industrial.erp.exception.BizException("原密码错误");
        }
        SysUser upd = new SysUser();
        upd.setId(uid);
        upd.setPassword(ENCODER.encode(newPwd));
        userMapper.updateById(upd);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        permService.requirePerm("system:user:del");
        SysUser u = userMapper.selectById(id);
        if (u == null) throw new com.industrial.erp.exception.BizException("用户不存在或已删除");
        userMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, id).set(SysUser::getDeleted, 1));
        operLogPublisher.publishDeleteSnapshot("用户管理", String.valueOf(id), u, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long id, String password) {
        permService.requireSuperAdmin();
        assertPasswordComplexity(password, "重置密码");
        SysUser user = new SysUser();
        user.setId(id);
        user.setPassword(ENCODER.encode(password));
        userMapper.updateById(user);
    }

    public List<Long> getRoleIds(Long userId) {
        return userMapper.selectRoleIdsByUserId(userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long userId, List<Long> roleIds) {
        permService.requirePerm("system:user:edit");
        userMapper.deleteUserRoles(userId);
        if (roleIds != null && !roleIds.isEmpty()) {
            userMapper.insertUserRolesBatch(userId, roleIds);
        }
    }
}
