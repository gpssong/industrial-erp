package com.industrial.erp.modules.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.purchase.entity.PurReturnDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PurReturnDetailMapper extends BaseMapper<PurReturnDetail> {
    List<PurReturnDetail> selectByReturnId(@Param("returnId") Long returnId);
}
