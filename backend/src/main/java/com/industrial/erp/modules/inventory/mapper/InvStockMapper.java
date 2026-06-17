package com.industrial.erp.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.inventory.entity.InvStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

@Mapper
public interface InvStockMapper extends BaseMapper<InvStock> {

    /**
     * 加锁查询 (悲观锁), 用于出库
     */
    InvStock selectForUpdate(@Param("warehouseId") Long warehouseId,
                             @Param("locationId") Long locationId,
                             @Param("productId") Long productId,
                             @Param("batchNo") String batchNo);

    /**
     * 累加库存数量与总成本 (入库)
     */
    int incrStock(@Param("warehouseId") Long warehouseId,
                  @Param("warehouseName") String warehouseName,
                  @Param("locationId") Long locationId,
                  @Param("locationName") String locationName,
                  @Param("productId") Long productId,
                  @Param("productCode") String productCode,
                  @Param("productName") String productName,
                  @Param("spec") String spec,
                  @Param("unitId") Long unitId,
                  @Param("unitName") String unitName,
                  @Param("batchNo") String batchNo,
                  @Param("qty") BigDecimal qty,
                  @Param("amount") BigDecimal amount,
                  @Param("bizDate") String bizDate);

    int updateQtyAndAvgCost(@Param("id") Long id,
                            @Param("qty") BigDecimal qty,
                            @Param("avgCost") BigDecimal avgCost,
                            @Param("totalCost") BigDecimal totalCost,
                            @Param("bizDate") String bizDate);
}
