package com.industrial.erp.modules.production.bill;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.production.entity.PrdFinishedIn;
import com.industrial.erp.modules.production.mapper.PrdFinishedInMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 成品入库 BillLoader (v1.1.102)
 *
 * <p>飞鹅云打印标签: 品名 / 规格 / 数量 / 批号 4 字段。
 * 这些字段都是 {@link PrdFinishedIn} 直接列, 无需 JOIN 商品表.
 */
@Component
public class PrdFinishedInBillLoader implements BillLoader {

    private final PrdFinishedInMapper finishedInMapper;

    public PrdFinishedInBillLoader(PrdFinishedInMapper finishedInMapper) {
        this.finishedInMapper = finishedInMapper;
    }

    @Override public String bizType() { return "FINISHED_IN"; }
    @Override public String templatePath() { return "print/prd_finished_in_feie.ftl"; }

    @Override
    public Map<String, Object> load(Long billId) {
        PrdFinishedIn bill = finishedInMapper.selectById(billId);
        if (bill == null) {
            throw BizException.of("成品入库单不存在: id=" + billId);
        }
        Map<String, Object> model = new HashMap<>();
        model.put("bill", bill);
        return model;
    }

    @Override
    public String billNo(Long billId) {
        PrdFinishedIn bill = finishedInMapper.selectById(billId);
        return bill == null ? null : bill.getBillNo();
    }
}
