package com.industrial.erp.modules.production.bill;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.production.entity.PrdBom;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.entity.PrdRequisitionDetail;
import com.industrial.erp.modules.production.mapper.PrdBomMapper;
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
    private final PrdBomMapper bomMapper;

    public PrdOrderBillLoader(PrdOrderMapper orderMapper,
                              PrdRequisitionDetailMapper reqDetailMapper,
                              BaseProductMapper productMapper,
                              PrdBomMapper bomMapper) {
        this.orderMapper = orderMapper;
        this.reqDetailMapper = reqDetailMapper;
        this.productMapper = productMapper;
        this.bomMapper = bomMapper;
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

        // 注入 BOM 名称/编码/备注 (飞鹅打印模板 ${order.bomName} 用, 修复 2026-07-27)
        if (order.getBomId() != null) {
            PrdBom bom = bomMapper.selectById(order.getBomId());
            if (bom != null) {
                order.setBomCode(bom.getBomCode());
                order.setBomName(bom.getBomName());
                if (bom.getRemark() != null) {
                    order.setBomRemark(bom.getRemark());
                }
            }
        }

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