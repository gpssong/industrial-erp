package com.industrial.erp.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.inventory.entity.InvCheckDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface InvCheckDetailMapper extends BaseMapper<InvCheckDetail> {
    List<InvCheckDetail> selectByCheckId(@Param("id") Long id);
}
