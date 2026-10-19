package com.industrial.erp.modules.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.modules.purchase.entity.PurInboundPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * v1.1.101 XK — 采购计划 mapper.
 *
 * <p>分页查询支持 planNo/supplierId/planStatus 三维过滤; App/PC 通用.
 */
@Mapper
public interface PurInboundPlanMapper extends BaseMapper<PurInboundPlan> {

    @Select("<script>" +
            "SELECT * FROM pur_inbound_plan " +
            "<where>" +
            "  deleted = 0 " +
            "  <if test=\"planNo != null and planNo != ''\">AND plan_no LIKE CONCAT('%', #{planNo}, '%')</if>" +
            "  <if test=\"supplierId != null\">AND supplier_id = #{supplierId}</if>" +
            "  <if test=\"planStatus != null and planStatus != ''\">AND plan_status = #{planStatus}</if>" +
            "</where>" +
            "ORDER BY id DESC" +
            "</script>")
    IPage<PurInboundPlan> selectPlanPage(
            IPage<PurInboundPlan> page,
            @Param("planNo") String planNo,
            @Param("supplierId") Long supplierId,
            @Param("planStatus") String planStatus);

    @Select("SELECT * FROM pur_inbound_plan WHERE id = #{id} AND deleted = 0")
    PurInboundPlan selectByIdRaw(@Param("id") Long id);

    @Select("SELECT * FROM pur_inbound_plan WHERE plan_no = #{planNo} AND deleted = 0 LIMIT 1")
    PurInboundPlan findByPlanNo(@Param("planNo") String planNo);

    @Select("SELECT * FROM pur_inbound_plan WHERE plan_status = #{status} AND deleted = 0 ORDER BY plan_date ASC")
    java.util.List<PurInboundPlan> listByStatus(@Param("status") String status);
}
