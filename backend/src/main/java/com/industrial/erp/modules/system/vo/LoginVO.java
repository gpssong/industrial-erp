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
    private List<SysMenu> menus;
    private Integer isAdmin;

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
}
