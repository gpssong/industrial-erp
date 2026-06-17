package com.industrial.erp.modules.outsource.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.outsource.entity.OutIssueDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OutIssueDetailMapper extends BaseMapper<OutIssueDetail> {
    List<OutIssueDetail> selectByIssueId(@Param("id") Long id);
}
