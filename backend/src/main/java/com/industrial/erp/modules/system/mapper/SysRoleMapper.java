package com.industrial.erp.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.system.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {
    List<Long> selectUserIdsByRoleId(@Param("roleId") Long roleId);
    void insertRoleMenuBatch(@Param("roleId") Long roleId, @Param("menuIds") List<Long> menuIds);
    void deleteRoleMenus(@Param("roleId") Long roleId);
    void insertUserRoleBatch(@Param("roleId") Long roleId, @Param("userIds") List<Long> userIds);
    void deleteUserRoles(@Param("roleId") Long roleId);

    /**
     * 取用户所有有效角色的 data_scope (1=全部 2=本部门及下级 3=本部门 4=本人)。
     * 用于 PermissionService 计算最终的数据范围 (取权限最大的, 即数字最小的)。
     */
    List<Integer> selectDataScopesByUserId(@Param("userId") Long userId);
}
