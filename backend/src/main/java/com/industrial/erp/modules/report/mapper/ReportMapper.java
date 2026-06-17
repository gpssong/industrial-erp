package com.industrial.erp.modules.report.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface ReportMapper {

    List<Map<String, Object>> salesSummary(@Param("startDate") String startDate, @Param("endDate") String endDate);
    List<Map<String, Object>> salesRanking(@Param("startDate") String startDate, @Param("endDate") String endDate, @Param("limit") Integer limit);
    List<Map<String, Object>> inventorySummary();
    List<Map<String, Object>> inventoryAging();
    List<Map<String, Object>> arapSummary(@Param("billType") String billType);
    List<Map<String, Object>> profitAnalysis(@Param("startDate") String startDate, @Param("endDate") String endDate);
    List<Map<String, Object>> dashboardKpi();
}
