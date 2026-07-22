package com.industrial.erp.modules.production.bill;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.base.service.ProductAttrInjector;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.entity.SalDeliveryDetail;
import com.industrial.erp.modules.sales.mapper.SalDeliveryDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalDeliveryMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 销售出库单 BillLoader (SAL_DELIVERY)
 */
@Component
public class SalDeliveryBillLoader implements BillLoader {

    private final SalDeliveryMapper mapper;
    private final SalDeliveryDetailMapper detailMapper;
    private final BaseWarehouseMapper warehouseMapper;
    private final BaseProductMapper productMapper;

    public SalDeliveryBillLoader(SalDeliveryMapper mapper,
                                  SalDeliveryDetailMapper detailMapper,
                                  BaseWarehouseMapper warehouseMapper,
                                  BaseProductMapper productMapper) {
        this.mapper = mapper;
        this.detailMapper = detailMapper;
        this.warehouseMapper = warehouseMapper;
        this.productMapper = productMapper;
    }

    @Override public String bizType() { return "SAL_DELIVERY"; }
    @Override public String templatePath() { return "print/sal_delivery_feie.ftl"; }

    @Override
    public Map<String, Object> load(Long billId) {
        SalDelivery bill = mapper.selectById(billId);
        if (bill == null) throw BizException.of("销售出库单不存在: id=" + billId);
        injectTransient(bill);
        List<SalDeliveryDetail> details = detailMapper.selectByDeliveryId(billId);
        // 批量注入商品色号 (避免 N+1)
        if (!details.isEmpty()) {
            @SuppressWarnings("unchecked")
            List rows = (List) details;
            ProductAttrInjector.injectColorNo(productMapper, rows,
                    d -> ((SalDeliveryDetail) d).getProductId(),
                    (d, v) -> ((SalDeliveryDetail) d).setPColorNo(v));
        }

        Map<String, Object> model = new HashMap<>();
        model.put("bill", bill);
        model.put("details", details);
        return model;
    }

    @Override
    public String billNo(Long billId) {
        SalDelivery bill = mapper.selectById(billId);
        return bill == null ? null : bill.getBillNo();
    }

    private void injectTransient(SalDelivery bill) {
        if (bill.getWarehouseId() != null) {
            var w = warehouseMapper.selectById(bill.getWarehouseId());
            if (w != null) bill.setWarehouseName(w.getWarehouseName());
        }
    }
}