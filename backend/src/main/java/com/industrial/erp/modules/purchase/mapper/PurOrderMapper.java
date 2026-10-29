package com.industrial.erp.modules.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.modules.purchase.entity.PurOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface PurOrderMapper extends BaseMapper<PurOrder> {

    /**
     * 分页查询 (带首个商品名称).
     * <p>
     * 背景: App 端「关联采购订单」选单器原先只显示单号, 看不出是哪个原料。
     * 与 SalDeliveryMapper / PurReturnMapper 同款写法 — EXISTS 子查询命中任一明细行的商品名,
     * 放在 SQL 内部, 不走 QueryWrapper.apply() 字符串拼接反模式。
     * <p>
     * 注意: 明细表 {@code pur_order_detail} 自带 product_name 冗余列, 直接取, 不用 JOIN base_product。
     */
    @Select("SELECT o.*, " +
            "(SELECT GROUP_CONCAT(d.product_name ORDER BY d.line_no SEPARATOR ', ') " +
            " FROM pur_order_detail d " +
            " WHERE d.order_id = o.id AND d.deleted = 0 LIMIT 1) AS firstProductName " +
            "FROM pur_order o " +
            "${ew.customSqlSegment}")
    IPage<PurOrder> selectPageWithProduct(IPage<PurOrder> page, @Param("ew") QueryWrapper<PurOrder> ew);
}
