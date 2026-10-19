package com.industrial.erp.modules.em.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.em.entity.BaseEquipment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BaseEquipmentMapper extends BaseMapper<BaseEquipment> {
    List<BaseEquipment> selectByWorkshopAndType(@Param("workshop") String workshop,
                                                @Param("type") String type);
}