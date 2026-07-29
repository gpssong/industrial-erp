package com.industrial.erp.modules.system.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.industrial.erp.modules.system.entity.SysMenu;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "登录返回")
public class LoginVO {
    private String token;
    private String tokenName = "Authorization";
    private Long userId;
    private String username;
    private String nickname;
    private String avatar;
    private Long deptId;
    private String deptName;
    private List<String> roles;
    private List<String> permissions;
    /** @deprecated v1.0.10+ 使用 pcMenus/appMenus 替代 */
    @Deprecated
    private List<SysMenu> menus;
    private List<SysMenu> pcMenus;
    private List<SysMenu> appMenus;
    private Integer isAdmin;
    /** v1.0.10+: 允许登录的端 (BOTH/PC/APP) */
    private String clientScope;
    /**
     * P1-8: 是否仍在使用 seed 默认密码.
     * <p>true 表示用户使用了 seed 硬编码的 admin/admin123, 前端应弹出"请修改密码"对话框, 强制改密后才能正常使用.
     */
    private Boolean passwordExpired;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getTokenName() { return tokenName; }
    public void setTokenName(String tokenName) { this.tokenName = tokenName; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public Long getDeptId() { return deptId; }
    public void setDeptId(Long deptId) { this.deptId = deptId; }
    public String getDeptName() { return deptName; }
    public void setDeptName(String deptName) { this.deptName = deptName; }
    public List<String> getRoles() { return roles; }
    public void setRoles(List<String> roles) { this.roles = roles; }
    public List<String> getPermissions() { return permissions; }
    public void setPermissions(List<String> permissions) { this.permissions = permissions; }
    public List<SysMenu> getMenus() { return menus; }
    public void setMenus(List<SysMenu> menus) { this.menus = menus; }
    public Integer getIsAdmin() { return isAdmin; }
    public void setIsAdmin(Integer isAdmin) { this.isAdmin = isAdmin; }
    public Boolean getPasswordExpired() { return passwordExpired; }
    public void setPasswordExpired(Boolean passwordExpired) { this.passwordExpired = passwordExpired; }
    public List<SysMenu> getPcMenus() { return pcMenus; }
    public void setPcMenus(List<SysMenu> pcMenus) { this.pcMenus = pcMenus; }
    public List<SysMenu> getAppMenus() { return appMenus; }
    public void setAppMenus(List<SysMenu> appMenus) { this.appMenus = appMenus; }
    public String getClientScope() { return clientScope; }
    public void setClientScope(String clientScope) { this.clientScope = clientScope; }
}
