package com.industrial.erp.modules.production.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.production.entity.PrdOrderMaterial;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PrdOrderMaterialMapper extends BaseMapper<PrdOrderMaterial> {

    /** 某工单的原料明细 (未删除, 按行号). */
    @Select("SELECT * FROM prd_order_material WHERE prd_order_id = #{orderId} AND deleted = 0 ORDER BY line_no ASC, id ASC")
    List<PrdOrderMaterial> selectByOrderId(@Param("orderId") Long orderId);
}
