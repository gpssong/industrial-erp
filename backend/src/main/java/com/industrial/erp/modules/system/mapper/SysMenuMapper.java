package com.industrial.erp.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.system.entity.SysMenu;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysMenuMapper extends BaseMapper<SysMenu> {
    List<SysMenu> selectMenusByUserId(Long userId);
    /** v1.0.10+ 按客户端类型过滤菜单 (PC/APP) */
    List<SysMenu> selectMenusByUserIdAndClient(@Param("userId") Long userId, @Param("clientType") String clientType);
    List<SysMenu> selectMenusByRoleId(Long roleId);
    /** v1.0.10+: 查询角色指定 client_type 已分配的菜单 */
    List<SysMenu> selectMenusByRoleIdAndClient(@Param("roleId") Long roleId, @Param("clientType") String clientType);
}
