package com.industrial.erp.modules.production.bill;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
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
 * <p>复用 PrdOrderService.detail 的逻辑: 加载订单 + JOIN 商品规格属性 + 领料明细
 */
@Component
public class PrdOrderBillLoader implements BillLoader {

    private final PrdOrderMapper orderMapper;
    private final PrdRequisitionDetailMapper reqDetailMapper;
    private final BaseProductMapper productMapper;

    public PrdOrderBillLoader(PrdOrderMapper orderMapper,
                              PrdRequisitionDetailMapper reqDetailMapper,
                              BaseProductMapper productMapper) {
        this.orderMapper = orderMapper;
        this.reqDetailMapper = reqDetailMapper;
        this.productMapper = productMapper;
    }

    @Override public String bizType() { return "PRD_ORDER"; }
    @Override public String templatePath() { return "print/prd_order_feie.ftl"; }

    @Override
    public Map<String, Object> load(Long billId) {
        PrdOrder order = orderMapper.selectById(billId);
        if (order == null) {
            throw BizException.of("生产单不存在: id=" + billId);
        }
        // 注入商品规格属性 (transient 字段, 默认 selectById 不会加载)
        if (order.getProductId() != null) {
            BaseProduct p = productMapper.selectById(order.getProductId());
            if (p != null) {
                order.setPThickness(p.getThickness());
                order.setPWidth(p.getWidth());
                order.setPDensity(p.getDensity());
                order.setPGramWeight(p.getGramWeight());
                order.setPMaterial(p.getMaterial());
                order.setPColorNo(p.getColorNo());
                order.setModel(p.getModel());
            }
        }
        // 加载领料明细
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