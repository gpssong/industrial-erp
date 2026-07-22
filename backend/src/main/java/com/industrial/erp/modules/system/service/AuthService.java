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
import com.industrial.erp.modules.system.mapper.SysLoginLogMapper;
import com.industrial.erp.modules.system.mapper.SysMenuMapper;
import com.industrial.erp.modules.system.mapper.SysRoleMapper;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
import com.industrial.erp.modules.system.entity.SysLoginLog;
import com.industrial.erp.modules.system.vo.LoginVO;
import com.industrial.erp.security.PermissionService;
import com.industrial.erp.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class AuthService {

    private final SysUserMapper userMapper;
    private final SysMenuMapper menuMapper;
    private final SysDeptMapper deptMapper;
    private final SysRoleMapper roleMapper;
    private final SysLoginLogMapper loginLogMapper;
    private final StringRedisTemplate redis;

    public AuthService(SysUserMapper userMapper, SysMenuMapper menuMapper, SysDeptMapper deptMapper,
                       SysRoleMapper roleMapper, SysLoginLogMapper loginLogMapper, StringRedisTemplate redis) {
        this.userMapper = userMapper;
        this.menuMapper = menuMapper;
        this.deptMapper = deptMapper;
        this.roleMapper = roleMapper;
        this.loginLogMapper = loginLogMapper;
        this.redis = redis;
    }

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);


    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    /**
     * 默认种子密码 (sql/09_seed_data.sql). 首次登录检测到使用此密码时, 强制要求修改.
     * <p>注意: BCrypt 同一明文每次 hash 不同 (salt), 因此不能直接比对 hash, 只能比对 ENCODER.matches.
     */
    private static final String DEFAULT_SEED_PASSWORD = "admin123";

    public Object generateCaptcha() {
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(120, 40, 4, 20);
        String key = IdUtil.fastSimpleUUID();
        redis.opsForValue().set(Constants.REDIS_LOGIN_LIMIT + "captcha:" + key, captcha.getCode(), 5, TimeUnit.MINUTES);
        java.util.Map<String, Object> r = new java.util.HashMap<>();
        r.put("captchaKey", key);
        r.put("captchaImage", captcha.getImageBase64Data());
        return r;
    }

    @Transactional(rollbackFor = Exception.class)
    public LoginVO login(LoginDTO dto, HttpServletRequest request) {
        // 解析登录上下文 (IP / UA)
        String ip = clientIp(request);
        String ua = request != null ? request.getHeader("User-Agent") : null;
        String browser = parseBrowser(ua);
        String os = parseOs(ua);

        try {
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
            int failCountInt = 0;
            try {
                failCountInt = Integer.parseInt(failCount);
            } catch (NumberFormatException ignore) {
                // Redis 中存储异常值时, 视为 0
            }
            if (StrUtil.isNotBlank(failCount) && failCountInt >= 5) {
                throw BizException.of("登录失败次数过多, 请5分钟后再试");
            }

            SysUser user = userMapper.selectByUsername(dto.getUsername());
            if (user == null) {
                incrFail(limitKey);
                throw new BizException("用户名或密码错误");
            }
            if (user.getStatus() == 0) {
                throw new BizException("账号已停用");
            }
            if (!ENCODER.matches(dto.getPassword(), user.getPassword())) {
                incrFail(limitKey);
                throw new BizException("用户名或密码错误");
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

            // P1-8: 检测默认密码 — 强制要求首次登录修改密码才能继续
            // 不能直接比对 hash (BCrypt salt 随机), 必须用 ENCODER.matches 校验
            boolean isDefaultPwd = ENCODER.matches(DEFAULT_SEED_PASSWORD, user.getPassword());
            vo.setPasswordExpired(isDefaultPwd);
            if (isDefaultPwd) {
                log.warn("[Auth] 用户 {} 仍使用默认 seed 密码, 强制改密", user.getUsername());
            }

            // 记录登录成功日志
            recordLogin(dto.getUsername(), 1, isDefaultPwd ? "登录成功 (默认密码, 待修改)" : "登录成功", ip, browser, os);
            log.info("用户登录: userId={}, username={}", user.getId(), user.getUsername());
            return vo;
        } catch (BizException e) {
            // 记录登录失败日志 (BizException 携带原始 msg)
            recordLogin(dto.getUsername(), 0, e.getMessage(), ip, browser, os);
            throw e;
        } catch (RuntimeException e) {
            // 系统异常也记录 (避免失败原因完全丢失)
            recordLogin(dto.getUsername(), 0, "系统异常: " + e.getMessage(), ip, browser, os);
            throw e;
        }
    }

    /**
     * 写入登录日志 (append-only, 不走事务)
     */
    private void recordLogin(String username, int status, String msg, String ip, String browser, String os) {
        try {
            SysLoginLog l = new SysLoginLog();
            l.setUsername(username);
            l.setIpAddress(ip);
            l.setBrowser(browser);
            l.setOs(os);
            l.setStatus(status);
            l.setMsg(msg);
            l.setLoginTime(LocalDateTime.now());
            loginLogMapper.insert(l);
        } catch (Exception e) {
            // 日志写入失败不影响登录主流程
            log.warn("写入登录日志失败: username={}, err={}", username, e.getMessage());
        }
    }

    private String clientIp(HttpServletRequest req) {
        if (req == null) return null;
        String ip = req.getHeader("X-Forwarded-For");
        if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) ip = req.getHeader("X-Real-IP");
        if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) ip = req.getRemoteAddr();
        return ip;
    }

    /** 极简 UA 解析: 不引依赖, 仅匹配关键字; 可能为 null. */
    private String parseBrowser(String ua) {
        if (ua == null) return null;
        ua = ua.toLowerCase();
        if (ua.contains("edg/") || ua.contains("edge")) return "Edge";
        if (ua.contains("chrome") && !ua.contains("chromium")) return "Chrome";
        if (ua.contains("safari") && !ua.contains("chrome")) return "Safari";
        if (ua.contains("firefox")) return "Firefox";
        if (ua.contains("micromessenger")) return "WeChat";
        if (ua.contains("postman")) return "Postman";
        if (ua.contains("curl")) return "Curl";
        return "Other";
    }

    private String parseOs(String ua) {
        if (ua == null) return null;
        ua = ua.toLowerCase();
        if (ua.contains("windows")) return "Windows";
        if (ua.contains("mac os") || ua.contains("macintosh")) return "macOS";
        if (ua.contains("android")) return "Android";
        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ios")) return "iOS";
        if (ua.contains("linux")) return "Linux";
        return "Other";
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

    /**
     * 重置指定用户的密码。
     *
     * <p>安全: 历史上此接口未做鉴权 + 在 Sa-Token 白名单中, 等同于任意人可重置 admin 密码.
     * 现在已移除白名单 + 在此强制要求超管. 调用方应该用
     * {@code SysUserService.resetPassword(id, pwd)} 走正常的权限校验路径.
     */
    public void setPassword(String username, String password) {
        if (!SecurityContext.isSuperAdmin()) {
            throw BizException.of(403, "只有超级管理员才能重置用户密码");
        }
        SysUser user = userMapper.selectByUsername(username);
        if (user == null) throw BizException.of("用户不存在: " + username);
        user.setPassword(ENCODER.encode(password));
        userMapper.updateById(user);
        log.warn("超管重置用户密码: targetUser={}, operator={}", username, SecurityContext.getUsername());
    }

}