package com.industrial.erp.modules.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.system.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    SysUser selectByUsername(@Param("username") String username);

    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    List<String> selectPermsByUserId(@Param("userId") Long userId);
    List<Long> selectRoleIdsByUserId(@Param("userId") Long userId);
    void insertUserRolesBatch(@Param("userId") Long userId, @Param("roleIds") List<Long> roleIds);
    void deleteUserRoles(@Param("userId") Long userId);
}
