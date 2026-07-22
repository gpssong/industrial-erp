package com.industrial.erp.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.inventory.vo.WarehouseStockSnapshotVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InvCheckMapper extends BaseMapper<com.industrial.erp.modules.inventory.entity.InvCheck> {

    /**
     * v1.0.8+ App 盘点预加载: 列出某仓库所有有库存商品的账面数快照.
     * <p>JOIN inv_stock (按 product_id 聚合 qty) + base_product 拿编码/名称.
     * <p>只列 qty != 0 的商品, 避免长尾; 若需空账面盘点, 可由 PC 端另开入口.
     */
    @Select("SELECT s.product_id AS productId, "
          + "p.product_code AS productCode, "
          + "p.product_name AS productName, "
          + "p.spec AS spec, "
          + "p.barcode AS barcode, "
          + "p.main_unit_id AS unitName, "
          + "SUM(s.qty) AS bookQty "
          + "FROM inv_stock s "
          + "INNER JOIN base_product p ON p.id = s.product_id AND p.deleted = 0 "
          + "WHERE s.warehouse_id = #{whId} AND s.deleted = 0 "
          + "GROUP BY s.product_id, p.product_code, p.product_name, p.spec, p.barcode, p.main_unit_id "
          + "HAVING SUM(s.qty) <> 0 "
          + "ORDER BY p.product_code ASC")
    List<WarehouseStockSnapshotVO> selectStockSnapshotByWarehouse(@Param("whId") Long warehouseId);
}
