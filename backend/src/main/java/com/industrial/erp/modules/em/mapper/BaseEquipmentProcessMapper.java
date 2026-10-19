package com.industrial.erp.modules.em.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.em.entity.BaseEquipmentProcess;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BaseEquipmentProcessMapper extends BaseMapper<BaseEquipmentProcess> {
    List<BaseEquipmentProcess> selectByEquipmentId(@Param("equipmentId") Long equipmentId);
    List<BaseEquipmentProcess> selectByProcessName(@Param("processName") String processName);
}