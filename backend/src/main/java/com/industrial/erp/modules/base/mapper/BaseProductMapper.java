package com.industrial.erp.modules.base.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.base.entity.BaseProduct;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface BaseProductMapper extends BaseMapper<BaseProduct> {
    BaseProduct selectByCode(@Param("code") String code);
    BaseProduct selectByBarcode(@Param("barcode") String barcode);
    List<Map<String, Object>> selectStockSummary(@Param("productId") Long productId);
}
