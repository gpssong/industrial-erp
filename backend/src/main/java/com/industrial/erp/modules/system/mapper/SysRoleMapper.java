package com.industrial.erp.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.system.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {
    List<Long> selectUserIdsByRoleId(@Param("roleId") Long roleId);
    void insertRoleMenuBatch(@Param("roleId") Long roleId, @Param("menuIds") List<Long> menuIds);
    /** v1.0.10+: clientType 批量授权 (entries = [{menuId, clientType}]) */
    void insertRoleMenuBatchByClient(@Param("roleId") Long roleId, @Param("entries") List<Map<String, String>> entries);
    void deleteRoleMenus(@Param("roleId") Long roleId);
    /** v1.0.10+: 按客户端类型删除角色菜单授权 */
    void deleteRoleMenusByClient(@Param("roleId") Long roleId, @Param("clientTypes") List<String> clientTypes);
    /** v1.1.8+: 按客户端类型 + 菜单 ID 列表删除角色菜单授权 (用于 App 端提交时清理 BOTH 同 ID 记录) */
    void deleteRoleMenusByClientAndMenuIds(@Param("roleId") Long roleId, @Param("clientTypes") List<String> clientTypes, @Param("menuIds") List<Long> menuIds);
    /** v1.1.8+: 迁移 BOTH 记录 — 当 APP/PC Tab 提交时, 处理旧的 BOTH 关联
     *  规则 (见 SysRoleService.grantMenusByClient):
     *    1. role 内已有 menu_id + 对端 client_type (otherCt) 独立记录 → 删 BOTH (两侧独立记录保留)
     *    2. 只有 BOTH 记录 → 升级 BOTH 为 otherCt (让另一端仍可用)
     *  在 menuIds 内的 BOTH 记录不处理 (它们将被作为本次 ct 重新插入)
     *  @param menuIds 本次提交的菜单 ID 列表 (可能为 null/空) */
    void migrateRoleMenuBOTH(@Param("roleId") Long roleId, @Param("clientType") String clientType, @Param("otherCt") String otherCt, @Param("menuIds") List<Long> menuIds);
    /** v1.1.8+: 删除 BOTH 记录中 menu_id NOT IN menuIds 且对端已有独立记录的 */
    void deleteRoleMenusBOTHNotIn(@Param("roleId") Long roleId, @Param("otherCt") String otherCt, @Param("menuIds") List<Long> menuIds);
    /** v1.1.8+: 查询 BOTH 记录中 menu_id 不在 menuIds 的 menu_id 列表 (Java 层分步处理) */
    List<Long> selectBOTHMenuIdsNotIn(@Param("roleId") Long roleId, @Param("menuIds") List<Long> menuIds);
    /** v1.1.8+: 查 role 的同 (role, menu) + 对端 client_type 记录数 */
    int countByRoleAndMenuAndClient(@Param("roleId") Long roleId, @Param("menuId") Long menuId, @Param("clientType") String clientType);
    /** v1.1.8+: 升级单条 BOTH → otherCt (避开 self-UPDATE self-SELECT 限制) */
    void upgradeSingleBOTHToOtherCt(@Param("roleId") Long roleId, @Param("menuId") Long menuId, @Param("otherCt") String otherCt);
    /** v1.1.8+: 把 role 全部 BOTH 升级为 otherCt (清空 Tab 时) */
    void upgradeAllBOTHToOtherCt(@Param("roleId") Long roleId, @Param("otherCt") String otherCt);
    void insertUserRoleBatch(@Param("roleId") Long roleId, @Param("userIds") List<Long> userIds);
    void deleteUserRoles(@Param("roleId") Long roleId);

    /**
     * 取用户所有有效角色的 data_scope (1=全部 2=本部门及下级 3=本部门 4=本人)。
     * 用于 PermissionService 计算最终的数据范围 (取权限最大的, 即数字最小的)。
     */
    List<Integer> selectDataScopesByUserId(@Param("userId") Long userId);
    /** v1.0.10+: 取用户所有角色的 client_scope 值 */
    List<String> selectClientScopesByUserId(@Param("userId") Long userId);
}
