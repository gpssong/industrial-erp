package com.industrial.erp.modules.production.bill;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.inventory.entity.InvCheck;
import com.industrial.erp.modules.inventory.entity.InvCheckDetail;
import com.industrial.erp.modules.inventory.mapper.InvCheckDetailMapper;
import com.industrial.erp.modules.inventory.mapper.InvCheckMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存盘点单 BillLoader (INV_CHECK)
 *
 * <p>注意: InvCheck 的 warehouseName 是持久化列 (非 transient), 直接 selectById 即可拿到.
 * 明细含 bookQty/actualQty/diffQty/diffAmount/diffType (账面/实盘/差异).
 */
@Component
public class InvCheckBillLoader implements BillLoader {

    private final InvCheckMapper mapper;
    private final InvCheckDetailMapper detailMapper;

    public InvCheckBillLoader(InvCheckMapper mapper, InvCheckDetailMapper detailMapper) {
        this.mapper = mapper;
        this.detailMapper = detailMapper;
    }

    @Override public String bizType() { return "INV_CHECK"; }
    @Override public String templatePath() { return "print/inv_check_feie.ftl"; }

    @Override
    public Map<String, Object> load(Long billId) {
        InvCheck bill = mapper.selectById(billId);
        if (bill == null) throw BizException.of("盘点单不存在: id=" + billId);
        List<InvCheckDetail> details = detailMapper.selectByCheckId(billId);
        bill.setDetails(details);

        Map<String, Object> model = new HashMap<>();
        model.put("bill", bill);
        model.put("details", details);
        return model;
    }

    @Override
    public String billNo(Long billId) {
        InvCheck bill = mapper.selectById(billId);
        return bill == null ? null : bill.getBillNo();
    }
}