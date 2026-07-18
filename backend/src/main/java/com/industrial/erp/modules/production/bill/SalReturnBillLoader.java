package com.industrial.erp.modules.production.bill;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.sales.entity.SalReturn;
import com.industrial.erp.modules.sales.entity.SalReturnDetail;
import com.industrial.erp.modules.sales.mapper.SalReturnDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalReturnMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 销售退货单 BillLoader (SAL_RETURN)
 */
@Component
public class SalReturnBillLoader implements BillLoader {

    private final SalReturnMapper mapper;
    private final SalReturnDetailMapper detailMapper;
    private final BaseWarehouseMapper warehouseMapper;

    public SalReturnBillLoader(SalReturnMapper mapper,
                                SalReturnDetailMapper detailMapper,
                                BaseWarehouseMapper warehouseMapper) {
        this.mapper = mapper;
        this.detailMapper = detailMapper;
        this.warehouseMapper = warehouseMapper;
    }

    @Override public String bizType() { return "SAL_RETURN"; }
    @Override public String templatePath() { return "print/sal_return_feie.ftl"; }

    @Override
    public Map<String, Object> load(Long billId) {
        SalReturn bill = mapper.selectById(billId);
        if (bill == null) throw BizException.of("销售退货单不存在: id=" + billId);
        if (bill.getWarehouseId() != null) {
            var w = warehouseMapper.selectById(bill.getWarehouseId());
            if (w != null) bill.setWarehouseName(w.getWarehouseName());
        }
        List<SalReturnDetail> details = detailMapper.selectByReturnId(billId);

        Map<String, Object> model = new HashMap<>();
        model.put("bill", bill);
        model.put("details", details);
        return model;
    }

    @Override
    public String billNo(Long billId) {
        SalReturn bill = mapper.selectById(billId);
        return bill == null ? null : bill.getBillNo();
    }
}