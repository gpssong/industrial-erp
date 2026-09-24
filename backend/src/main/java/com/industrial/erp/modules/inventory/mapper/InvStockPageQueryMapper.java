package com.industrial.erp.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * 库存查询分页 — 以 base_product 为驱动表, LEFT JOIN inv_stock 聚合库存.
 * <p>
 * v1.1.62: 原 {@code InvStockController.stockPage} 直接查 inv_stock 表 + {@code qty > 0} 严格过滤,
 *   导致<strong>库存为 0 且无 inv_stock 行</strong>的产品查不到 (用户截图的"塑料袋30*38*0.16" Total 0 案例).
 *   改为 base_product 驱动 + LEFT JOIN, 即使无 inv_stock 行也能命中, 库存列全 0/空.
 */
@Mapper
public interface InvStockPageQueryMapper {

    /**
     * 库存查询分页.
     *
     * @param page        MyBatis-Plus 分页参数 (pageNum/pageSize)
     * @param keyword     关键字模糊匹配 product_code/product_name/spec, null/空 = 不加过滤
     * @param warehouseId 仓库过滤, null = 不加过滤
     * @return IPage 包含 Map 记录:
     *   - productId, productCode, productName, spec, unitId, unitName
     *   - warehouseId, warehouseName (按所有仓库聚合, 取 MIN; 库存=0 时为 null)
     *   - qty, availableQty, lockQty, avgCost, totalCost (库存=0 时全为 0/null)
     *   - batchNo (库存=0 时为 null)
     *   - lastInDate (库存=0 时为 null)
     */
    IPage<Map<String, Object>> selectStockPage(IPage<Map<String, Object>> page,
                                               @Param("keyword") String keyword,
                                               @Param("warehouseId") Long warehouseId);
}