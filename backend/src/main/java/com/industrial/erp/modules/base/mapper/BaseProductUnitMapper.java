package com.industrial.erp.modules.base.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.base.entity.BaseProductUnit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BaseProductUnitMapper extends BaseMapper<BaseProductUnit> {
    List<BaseProductUnit> selectByProductId(@Param("productId") Long productId);
    BaseProductUnit selectMainUnit(@Param("productId") Long productId);
}
