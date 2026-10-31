package com.industrial.erp.modules.report.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface ReportMapper {

    List<Map<String, Object>> salesSummary(@Param("startDate") String startDate, @Param("endDate") String endDate);
    List<Map<String, Object>> salesRanking(@Param("startDate") String startDate, @Param("endDate") String endDate, @Param("limit") Integer limit);

    /**
     * v1.1.144 — 「按客户/时间段查销售汇总」的唯一 SQL 出口 (AI 助手与 v1.1.145 报表中心共用)。
     * 口径同 {@link #salesRanking}: 只算已审核/完成的销售出库单, 金额取含税销售额。
     *
     * @param customerId 客户 ID 精确过滤 (报表页下拉用; AI 侧传 null)
     * @param customer   客户名关键字 (可空, LIKE 模糊)
     * @param groupBy    {@code month} = 客户+月份出行; 其他值 = 整段按客户出行
     */
    List<Map<String, Object>> aiSalesSummary(@Param("startDate") String startDate, @Param("endDate") String endDate,
                                             @Param("customerId") Long customerId,
                                             @Param("customer") String customer, @Param("groupBy") String groupBy,
                                             @Param("limit") Integer limit);

    /**
     * v1.1.145 — 报表中心「销售明细」: 一行 = 一张出库单的一个商品行。
     * 口径与 {@link #aiSalesSummary} 同一个过滤段 (见 XML 的 {@code salesDetailWhere}),
     * 所以明细行加总 = 汇总里的销售额。
     */
    List<Map<String, Object>> salesDetailLines(@Param("startDate") String startDate, @Param("endDate") String endDate,
                                               @Param("customerId") Long customerId, @Param("customer") String customer,
                                               @Param("product") String product,
                                               @Param("offset") Integer offset, @Param("limit") Integer limit);

    /**
     * v1.1.145 — 明细的合计行 (行数/单数/客户数/数量/未税/税额/含税/成本)。
     * 与 {@link #salesDetailLines} 同一过滤段, 但**不分页** —— 合计必须是全查询范围的,
     * 前端拿当前页累加会得到一个随翻页变化的假合计。
     */
    Map<String, Object> salesDetailTotals(@Param("startDate") String startDate, @Param("endDate") String endDate,
                                          @Param("customerId") Long customerId, @Param("customer") String customer,
                                          @Param("product") String product);

    List<Map<String, Object>> inventorySummary();
    List<Map<String, Object>> inventoryAging();
    List<Map<String, Object>> arapSummary(@Param("billType") String billType);
    List<Map<String, Object>> profitAnalysis(@Param("startDate") String startDate, @Param("endDate") String endDate);
    List<Map<String, Object>> dashboardKpi();
}
