package com.industrial.erp.modules.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.modules.workflow.entity.WfEmergencyPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WfEmergencyPlanMapper extends BaseMapper<WfEmergencyPlan> {

    @Select("<script>" +
            "SELECT r.* FROM wf_emergency_plan r " +
            "<where> r.deleted = 0 " +
            "  <if test=\"tenantId != null\">AND r.tenant_id = #{tenantId}</if>" +
            "  <if test=\"billStatus != null and billStatus != ''\">AND r.bill_status = #{billStatus}</if>" +
            "  <if test=\"overdue != null and overdue\">AND r.next_drill_date IS NOT NULL AND r.next_drill_date &lt; CURDATE()</if>" +
            "</where> " +
            "ORDER BY r.id DESC" +
            "</script>")
    IPage<WfEmergencyPlan> selectPageList(IPage<WfEmergencyPlan> page,
            @Param("tenantId") Long tenantId,
            @Param("billStatus") String billStatus,
            @Param("overdue") Boolean overdue);
}
