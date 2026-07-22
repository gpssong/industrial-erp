package com.industrial.erp.modules.production.bill;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.base.service.ProductAttrInjector;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import com.industrial.erp.modules.purchase.entity.PurReceiptDetail;
import com.industrial.erp.modules.purchase.mapper.PurReceiptDetailMapper;
import com.industrial.erp.modules.purchase.mapper.PurReceiptMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 采购入库单 BillLoader (PUR_RECEIPT)
 */
@Component
public class PurReceiptBillLoader implements BillLoader {

    private final PurReceiptMapper mapper;
    private final PurReceiptDetailMapper detailMapper;
    private final BaseWarehouseMapper warehouseMapper;
    private final BaseProductMapper productMapper;

    public PurReceiptBillLoader(PurReceiptMapper mapper,
                                 PurReceiptDetailMapper detailMapper,
                                 BaseWarehouseMapper warehouseMapper,
                                 BaseProductMapper productMapper) {
        this.mapper = mapper;
        this.detailMapper = detailMapper;
        this.warehouseMapper = warehouseMapper;
        this.productMapper = productMapper;
    }

    @Override public String bizType() { return "PUR_RECEIPT"; }
    @Override public String templatePath() { return "print/pur_receipt_feie.ftl"; }

    @Override
    public Map<String, Object> load(Long billId) {
        PurReceipt bill = mapper.selectById(billId);
        if (bill == null) throw BizException.of("采购入库单不存在: id=" + billId);
        if (bill.getWarehouseId() != null) {
            var w = warehouseMapper.selectById(bill.getWarehouseId());
            if (w != null) bill.setWarehouseName(w.getWarehouseName());
        }
        List<PurReceiptDetail> details = detailMapper.selectByReceiptId(billId);
        // 批量注入商品色号 (避免 N+1)
        if (!details.isEmpty()) {
            @SuppressWarnings("unchecked")
            List rows = (List) details;
            ProductAttrInjector.injectColorNo(productMapper, rows,
                    d -> ((PurReceiptDetail) d).getProductId(),
                    (d, v) -> ((PurReceiptDetail) d).setPColorNo(v));
        }

        Map<String, Object> model = new HashMap<>();
        model.put("bill", bill);
        model.put("details", details);
        return model;
    }

    @Override
    public String billNo(Long billId) {
        PurReceipt bill = mapper.selectById(billId);
        return bill == null ? null : bill.getBillNo();
    }
}