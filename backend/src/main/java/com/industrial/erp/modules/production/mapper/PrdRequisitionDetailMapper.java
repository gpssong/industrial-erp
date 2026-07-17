package com.industrial.erp.modules.production.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.industrial.erp.modules.production.entity.PrdRequisitionDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface PrdRequisitionDetailMapper extends BaseMapper<PrdRequisitionDetail> {
    List<PrdRequisitionDetail> selectByRequisitionId(@Param("reqId") Long reqId);
    /**
     * 按 prd_order_id 跨所有领料单汇总明细 (按领料单日期+行号排序, 用于生产加工单打印)
     */
    List<PrdRequisitionDetail> selectByPrdOrderId(@Param("orderId") Long orderId);
}
