package com.industrial.erp.modules.inventory.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.inventory.entity.InvLedger;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface InvLedgerQueryMapper extends BaseMapper<InvLedger> {
    List<Map<String, Object>> selectStockByProduct(@Param("productId") Long productId);
    List<Map<String, Object>> selectStockAll();
}
