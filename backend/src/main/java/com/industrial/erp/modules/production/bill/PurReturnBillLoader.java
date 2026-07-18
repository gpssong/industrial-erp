package com.industrial.erp.modules.production.bill;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.purchase.entity.PurReturn;
import com.industrial.erp.modules.purchase.entity.PurReturnDetail;
import com.industrial.erp.modules.purchase.mapper.PurReturnDetailMapper;
import com.industrial.erp.modules.purchase.mapper.PurReturnMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 采购退货单 BillLoader (PUR_RETURN)
 */
@Component
public class PurReturnBillLoader implements BillLoader {

    private final PurReturnMapper mapper;
    private final PurReturnDetailMapper detailMapper;
    private final BaseWarehouseMapper warehouseMapper;

    public PurReturnBillLoader(PurReturnMapper mapper,
                                PurReturnDetailMapper detailMapper,
                                BaseWarehouseMapper warehouseMapper) {
        this.mapper = mapper;
        this.detailMapper = detailMapper;
        this.warehouseMapper = warehouseMapper;
    }

    @Override public String bizType() { return "PUR_RETURN"; }
    @Override public String templatePath() { return "print/pur_return_feie.ftl"; }

    @Override
    public Map<String, Object> load(Long billId) {
        PurReturn bill = mapper.selectById(billId);
        if (bill == null) throw BizException.of("采购退货单不存在: id=" + billId);
        if (bill.getWarehouseId() != null) {
            var w = warehouseMapper.selectById(bill.getWarehouseId());
            if (w != null) bill.setWarehouseName(w.getWarehouseName());
        }
        List<PurReturnDetail> details = detailMapper.selectByReturnId(billId);

        Map<String, Object> model = new HashMap<>();
        model.put("bill", bill);
        model.put("details", details);
        return model;
    }

    @Override
    public String billNo(Long billId) {
        PurReturn bill = mapper.selectById(billId);
        return bill == null ? null : bill.getBillNo();
    }
}