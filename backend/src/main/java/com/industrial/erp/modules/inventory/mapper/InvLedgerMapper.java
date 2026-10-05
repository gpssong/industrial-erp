package com.industrial.erp.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.inventory.entity.InvLedger;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface InvLedgerMapper extends BaseMapper<InvLedger> {

    /**
     * v1.1.75 任务3 补货预测: 按商品聚合近 N 天的<b>出库</b>总量 (biz_direction=-1).
     * 用于算日均消耗 → 可售天数 / 补货建议. 返回每商品: productId/productCode/productName/totalOut.
     * 只读统计, 不改动数据.
     */
    @Select("<script>" +
            "SELECT l.product_id AS productId, MAX(l.product_code) AS productCode, MAX(l.product_name) AS productName, " +
            "SUM(l.qty) AS totalOut " +
            "FROM inv_ledger l " +
            "WHERE l.biz_direction = -1 AND l.deleted = 0 AND l.biz_date &gt;= #{sinceDate} " +
            "GROUP BY l.product_id " +
            "ORDER BY totalOut DESC LIMIT #{limit}" +
            "</script>")
    List<Map<String, Object>> sumOutByProduct(@Param("sinceDate") java.time.LocalDate sinceDate, @Param("limit") int limit);
}
