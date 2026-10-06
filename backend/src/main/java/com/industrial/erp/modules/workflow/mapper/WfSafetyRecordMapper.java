package com.industrial.erp.modules.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.modules.workflow.entity.WfSafetyRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WfSafetyRecordMapper extends BaseMapper<WfSafetyRecord> {

    @Select("<script>" +
            "SELECT r.* FROM wf_safety_record r " +
            "<where> r.deleted = 0 " +
            "  <if test=\"site != null and site != ''\">AND r.site LIKE CONCAT('%', #{site}, '%')</if>" +
            "  <if test=\"billStatus != null and billStatus != ''\">AND r.bill_status = #{billStatus}</if>" +
            "  <if test=\"overdue != null and overdue\">AND r.next_due_date IS NOT NULL AND r.next_due_date &lt; CURDATE()</if>" +
            "</where> " +
            "ORDER BY r.id DESC" +
            "</script>")
    IPage<WfSafetyRecord> selectPageList(IPage<WfSafetyRecord> page,
            @Param("site") String site,
            @Param("billStatus") String billStatus,
            @Param("overdue") Boolean overdue);
}
