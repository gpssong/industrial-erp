package com.industrial.erp.modules.system.service;

import com.industrial.erp.modules.system.entity.SysMenu;
import com.industrial.erp.modules.system.mapper.SysMenuMapper;
import com.industrial.erp.modules.system.vo.LoginVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * v1.1.191 — /auth/me 实测 204KB 未压缩, 其中 pcMenus(86.5KB) 与 menus(同一份 86.5KB) 对 App 完全无用:
 * App 只读 appMenus + permissions. 这里锁住「按 X-Client-Type 裁剪」的语义.
 */
@DisplayName("AuthService: 菜单按客户端类型裁剪")
class AuthServiceMenuScopeTest {

    private final SysMenuMapper menuMapper = mock(SysMenuMapper.class);
    private final AuthService service = new AuthService(
            mock(com.industrial.erp.modules.system.mapper.SysUserMapper.class),
            menuMapper,
            mock(com.industrial.erp.modules.system.mapper.SysDeptMapper.class),
            mock(com.industrial.erp.modules.system.mapper.SysRoleMapper.class),
            mock(com.industrial.erp.modules.system.mapper.SysLoginLogMapper.class),
            mock(org.springframework.data.redis.core.StringRedisTemplate.class));

    private static SysMenu menu(long id, String name) {
        SysMenu m = new SysMenu();
        m.setId(id);
        m.setMenuName(name);
        return m;
    }

    @Test
    @DisplayName("APP 端只查 appMenus, 不再返回 pcMenus / menus")
    void appClientGetsOnlyAppMenus() {
        when(menuMapper.selectMenusByUserIdAndClient(7L, "APP"))
                .thenReturn(List.of(menu(2, "工作台")));

        LoginVO vo = new LoginVO();
        service.applyMenusByClient(vo, "APP", 7L);

        assertThat(vo.getAppMenus()).extracting(SysMenu::getId).containsExactly(2L);
        assertThat(vo.getPcMenus()).isNull();
        assertThat(vo.getMenus()).isNull();
        verify(menuMapper, never()).selectMenusByUserIdAndClient(eq(7L), eq("PC"));
    }

    @Test
    @DisplayName("PC 端(含不带 header 的旧调用)保持原样: pcMenus + 兼容字段 menus 同一份")
    void pcClientKeepsBackwardsCompatibleMenus() {
        List<SysMenu> pc = List.of(menu(1, "系统管理"));
        when(menuMapper.selectMenusByUserIdAndClient(7L, "PC")).thenReturn(pc);
        when(menuMapper.selectMenusByUserIdAndClient(7L, "APP")).thenReturn(List.of(menu(2, "工作台")));

        LoginVO vo = new LoginVO();
        service.applyMenusByClient(vo, null, 7L);

        assertThat(vo.getPcMenus()).isSameAs(pc);
        assertThat(vo.getMenus()).isSameAs(pc);
    }
}
