package com.industrial.erp.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.inventory.entity.InvStock;
import org.apache.ibatis.annotations.*;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

@Mapper
public interface InvStockMapper extends BaseMapper<InvStock> {

    @Select("<script>SELECT * FROM inv_stock WHERE warehouse_id = #{wId} AND product_id = #{pId} AND deleted = 0 <if test='bn != null and bn != \"\"'> AND batch_no = #{bn}</if> <if test='bn == null or bn == \"\"'> AND batch_no IS NULL</if> FOR UPDATE</script>")
    InvStock selectForUpdate(@Param("wId") Long warehouseId, @Param("pId") Long productId, @Param("bn") String batchNo);
}
