package com.industrial.erp.modules.system.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.industrial.erp.common.Constants;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.system.dto.LoginDTO;
import com.industrial.erp.modules.system.entity.SysDept;
import com.industrial.erp.modules.system.entity.SysMenu;
import com.industrial.erp.modules.system.entity.SysRole;
import com.industrial.erp.modules.system.entity.SysUser;
import com.industrial.erp.modules.system.mapper.SysDeptMapper;
import com.industrial.erp.modules.system.mapper.SysMenuMapper;
import com.industrial.erp.modules.system.mapper.SysRoleMapper;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import com.industrial.erp.modules.system.vo.LoginVO;
import com.industrial.erp.security.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class AuthService {

    private final SysUserMapper userMapper;
    private final SysMenuMapper menuMapper;
    private final SysDeptMapper deptMapper;
    private final SysRoleMapper roleMapper;
    private final StringRedisTemplate redis;

    public AuthService(SysUserMapper userMapper, SysMenuMapper menuMapper, SysDeptMapper deptMapper,
                       SysRoleMapper roleMapper, StringRedisTemplate redis) {
        this.userMapper = userMapper;
        this.menuMapper = menuMapper;
        this.deptMapper = deptMapper;
        this.roleMapper = roleMapper;
        this.redis = redis;
    }

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);


    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    public Object generateCaptcha() {
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(120, 40, 4, 20);
        String key = IdUtil.fastSimpleUUID();
        redis.opsForValue().set(Constants.REDIS_LOGIN_LIMIT + "captcha:" + key, captcha.getCode(), 5, TimeUnit.MINUTES);
        java.util.Map<String, Object> r = new java.util.HashMap<>();
        r.put("captchaKey", key);
        r.put("captchaImage", captcha.getImageBase64Data());
        return r;
    }

    public LoginVO login(LoginDTO dto) {
        // 校验图形验证码 (生产环境启用)
        if (StrUtil.isNotBlank(dto.getCaptchaKey())) {
            String code = redis.opsForValue().get(Constants.REDIS_LOGIN_LIMIT + "captcha:" + dto.getCaptchaKey());
            if (code == null) throw BizException.of("验证码已过期");
            if (!code.equalsIgnoreCase(dto.getCaptchaCode())) throw BizException.of("验证码错误");
            redis.delete(Constants.REDIS_LOGIN_LIMIT + "captcha:" + dto.getCaptchaKey());
        }

        // 登录失败次数限制
        String limitKey = Constants.REDIS_LOGIN_LIMIT + dto.getUsername();
        String failCount = redis.opsForValue().get(limitKey);
        if (StrUtil.isNotBlank(failCount) && Integer.parseInt(failCount) >= 5) {
            throw BizException.of("登录失败次数过多, 请5分钟后再试");
        }

        SysUser user = userMapper.selectByUsername(dto.getUsername());
        if (user == null) {
            incrFail(limitKey);
            throw BizException.of("用户名或密码错误");
        }
        if (user.getStatus() == 0) {
            throw BizException.of("账号已停用");
        }
        if (!ENCODER.matches(dto.getPassword(), user.getPassword())) {
            incrFail(limitKey);
            throw BizException.of("用户名或密码错误");
        }

        // 登录 Sa-Token
        StpUtil.login(user.getId());
        StpUtil.getSession().set("username", user.getUsername());
        StpUtil.getSession().set("isAdmin", user.getIsAdmin());
        // 修复: 原代码 user.getIsAdmin() == 1 ? 1L : 1L 两个分支相同, 现统一写 DEFAULT_TENANT
        // 后续接入多租户时, 此处改为读取 user.getTenantId()
        StpUtil.getSession().set(Constants.CURRENT_TENANT, Constants.DEFAULT_TENANT);

        // 预计算并缓存 data_scope (取多角色中权限最大, 即数字最小)
        Integer dataScope = computeDataScope(user.getId());
        if (dataScope != null) {
            StpUtil.getSession().set(PermissionService.SESSION_DATA_SCOPE, dataScope);
        }

        // 更新最后登录信息
        user.setLastLoginTime(LocalDateTime.now());
        user.setUpdateBy(user.getId());
        userMapper.updateById(user);

        // 清除失败计数
        redis.delete(limitKey);

        // 拼装返回
        LoginVO vo = new LoginVO();
        vo.setToken(StpUtil.getTokenValue());
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(StrUtil.blankToDefault(user.getNickname(), user.getUsername()));
        vo.setAvatar(user.getAvatar());
        vo.setDeptId(user.getDeptId());
        if (user.getDeptId() != null) {
            SysDept dept = deptMapper.selectById(user.getDeptId());
            if (dept != null) vo.setDeptName(dept.getDeptName());
        }
        vo.setRoles(userMapper.selectRoleCodesByUserId(user.getId()));
        vo.setPermissions(userMapper.selectPermsByUserId(user.getId()));
        vo.setMenus(menuMapper.selectMenusByUserId(user.getId()));
        vo.setIsAdmin(user.getIsAdmin());

        log.info("用户登录: userId={}, username={}", user.getId(), user.getUsername());
        return vo;
    }

    public void logout() {
        StpUtil.logout();
    }

    public LoginVO currentUser() {
        if (!StpUtil.isLogin()) throw BizException.of(401, "未登录");
        Long uid = Long.valueOf(StpUtil.getLoginId().toString());
        SysUser user = userMapper.selectById(uid);
        if (user == null) throw BizException.of(401, "用户不存在");
        LoginVO vo = new LoginVO();
        BeanUtil.copyProperties(user, vo, "password");
        vo.setRoles(userMapper.selectRoleCodesByUserId(uid));
        vo.setPermissions(userMapper.selectPermsByUserId(uid));
        vo.setMenus(menuMapper.selectMenusByUserId(uid));
        return vo;
    }

    private void incrFail(String key) {
        Long n = redis.opsForValue().increment(key);
        if (n != null && n == 1L) {
            redis.expire(key, 5, TimeUnit.MINUTES);
        }
    }

    /**
     * 计算用户的数据范围: 取多角色中权限最大的 (即 data_scope 数字最小的)
     * 超级管理员固定为 SCOPE_ALL
     */
    private Integer computeDataScope(Long userId) {
        if (userId == null) return PermissionService.SCOPE_SELF;
        // 超级管理员判断与 SecurityContext 一致
        if (Constants.SUPER_ADMIN_ID.equals(userId)) return PermissionService.SCOPE_ALL;
        List<Integer> scopes = roleMapper.selectDataScopesByUserId(userId);
        if (scopes == null || scopes.isEmpty()) return PermissionService.SCOPE_SELF;
        return scopes.stream().filter(s -> s != null).min(Integer::compareTo).orElse(PermissionService.SCOPE_SELF);
    }

    public void setPassword(String username, String password) {
        SysUser user = userMapper.selectByUsername(username);
        if (user != null) {
            user.setPassword(ENCODER.encode(password));
            userMapper.updateById(user);
        }
    }

}