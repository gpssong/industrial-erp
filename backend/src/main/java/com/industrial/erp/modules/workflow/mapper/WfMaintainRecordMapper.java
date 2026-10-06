package com.industrial.erp.modules.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.industrial.erp.modules.workflow.entity.WfMaintainRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WfMaintainRecordMapper extends BaseMapper<WfMaintainRecord> {

    @Select("<script>" +
            "SELECT r.* FROM wf_maintain_record r " +
            "<where> r.deleted = 0 " +
            "  <if test=\"deviceName != null and deviceName != ''\">AND r.device_name LIKE CONCAT('%', #{deviceName}, '%')</if>" +
            "  <if test=\"billStatus != null and billStatus != ''\">AND r.bill_status = #{billStatus}</if>" +
            "  <if test=\"overdue != null and overdue\">AND r.next_due_date IS NOT NULL AND r.next_due_date &lt; CURDATE()</if>" +
            "</where> " +
            "ORDER BY r.id DESC" +
            "</script>")
    IPage<WfMaintainRecord> selectPageList(IPage<WfMaintainRecord> page,
            @Param("deviceName") String deviceName,
            @Param("billStatus") String billStatus,
            @Param("overdue") Boolean overdue);
}
