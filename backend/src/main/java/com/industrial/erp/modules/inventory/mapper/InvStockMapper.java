package com.industrial.erp.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.inventory.entity.InvStock;
import org.apache.ibatis.annotations.*;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface InvStockMapper extends BaseMapper<InvStock> {

    /**
     * 出/入库原子锁定查询 (带 FOR UPDATE).
     * <p>
     * batchNo 三态处理 (v1.1.7+): 入参空串 与 null 视为同义, 都查 batch_no IS NULL;
     * 非空字符串精确匹配 batch_no = #{bn}. 这样可以避免前端空字符串 与 NULL OGNL 边界行为不一致.
     *
     * @param bn normalized batchNo ('' 或 null 都视作 NULL 语义)
     */
    @Select("<script>SELECT * FROM inv_stock WHERE warehouse_id = #{wId} AND product_id = #{pId} AND deleted = 0 "
        + "<choose>"
        + "<when test=\"bn != null and bn != ''\">AND batch_no = #{bn}</when>"
        + "<otherwise>AND batch_no IS NULL</otherwise>"
        + "</choose>"
        + " FOR UPDATE</script>")
    InvStock selectForUpdate(@Param("wId") Long warehouseId, @Param("pId") Long productId, @Param("bn") String batchNo);

    /**
     * 列出仓库+商品下所有未删除库存 (用于 库存不存在 时展示可选批次).
     * 排序: qty 降序, 最早入库优先 (FIFO 出库语义).
     */
    @Select("SELECT * FROM inv_stock WHERE warehouse_id = #{wId} AND product_id = #{pId} AND deleted = 0 "
        + "ORDER BY qty DESC, COALESCE(last_in_date, '1970-01-01') ASC, id ASC")
    List<InvStock> listByWarehouseAndProduct(@Param("wId") Long warehouseId, @Param("pId") Long productId);

    /**
     * v1.0.8+ 盘点账面数聚合: 某仓库某商品的所有未删除库存 qty 之和.
     * 不区分批次 — App 端无批次信息, 后续审核时按库存现状调整.
     */
    @Select("SELECT IFNULL(SUM(qty), 0) FROM inv_stock "
          + "WHERE warehouse_id = #{whId} AND product_id = #{pId} AND deleted = 0")
    BigDecimal sumQtyByWarehouseAndProduct(@Param("whId") Long warehouseId, @Param("pId") Long productId);
}

