package com.industrial.erp.modules.production.bill;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.service.ProductAttrInjector;
import com.industrial.erp.modules.sales.entity.SalOrder;
import com.industrial.erp.modules.sales.entity.SalOrderDetail;
import com.industrial.erp.modules.sales.mapper.SalOrderDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalOrderMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 销售订单 BillLoader (SAL_ORDER)
 */
@Component
public class SalOrderBillLoader implements BillLoader {

    private final SalOrderMapper mapper;
    private final SalOrderDetailMapper detailMapper;
    private final BaseProductMapper productMapper;

    public SalOrderBillLoader(SalOrderMapper mapper,
                              SalOrderDetailMapper detailMapper,
                              BaseProductMapper productMapper) {
        this.mapper = mapper;
        this.detailMapper = detailMapper;
        this.productMapper = productMapper;
    }

    @Override public String bizType() { return "SAL_ORDER"; }
    @Override public String templatePath() { return "print/sal_order_feie.ftl"; }

    @Override
    public Map<String, Object> load(Long billId) {
        SalOrder bill = mapper.selectById(billId);
        if (bill == null) throw BizException.of("销售订单不存在: id=" + billId);
        List<SalOrderDetail> details = detailMapper.selectByOrderId(billId);
        // 注入商品型号 (打印模板 "型号" 列)
        if (!details.isEmpty()) {
            ProductAttrInjector.inject(productMapper, details,
                    d -> d.getProductId(),
                    (d, v) -> d.setPModel(v),
                    p -> p.getModel());
        }
        Map<String, Object> model = new HashMap<>();
        model.put("bill", bill);
        model.put("details", details);
        return model;
    }

    @Override
    public String billNo(Long billId) {
        SalOrder bill = mapper.selectById(billId);
        return bill == null ? null : bill.getBillNo();
    }
}
