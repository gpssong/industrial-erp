package com.industrial.erp.modules.production.bill;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.entity.PrdRequisitionDetail;
import com.industrial.erp.modules.production.mapper.PrdOrderMapper;
import com.industrial.erp.modules.production.mapper.PrdRequisitionDetailMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 生产加工单 BillLoader
 *
 * <p>复用现有 FeiePrintService.getOrderWithDetails 的逻辑, 渲染模板 {@code print/prd_order_feie.ftl}
 */
@Component
public class PrdOrderBillLoader implements BillLoader {

    private final PrdOrderMapper orderMapper;
    private final PrdRequisitionDetailMapper reqDetailMapper;

    public PrdOrderBillLoader(PrdOrderMapper orderMapper,
                              PrdRequisitionDetailMapper reqDetailMapper) {
        this.orderMapper = orderMapper;
        this.reqDetailMapper = reqDetailMapper;
    }

    @Override
    public String bizType() {
        return "PRD_ORDER";
    }

    @Override
    public String templatePath() {
        return "print/prd_order_feie.ftl";
    }

    @Override
    public Map<String, Object> load(Long billId) {
        PrdOrder order = orderMapper.selectById(billId);
        if (order == null) {
            throw BizException.of("生产单不存在: id=" + billId);
        }
        // 加载领料明细 (跨所有领料单, 按时间排序)
        List<PrdRequisitionDetail> details = reqDetailMapper.selectByPrdOrderId(billId);
        order.setRequisitionDetails(details);

        Map<String, Object> model = new HashMap<>();
        model.put("order", order);
        model.put("details", details);
        return model;
    }

    @Override
    public String billNo(Long billId) {
        PrdOrder order = orderMapper.selectById(billId);
        return order == null ? null : order.getBillNo();
    }
}