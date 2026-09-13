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
        // v1.1.38+: 注入商品 规格/型号/色号 (打印模板明细表字段补齐, 浏览器打印已通过 Service.detail() 注入, 这里飞鹅打印再补一次)
        if (!details.isEmpty()) {
            // spec: 直接写到实体字段 (BaseProduct.spec → SalOrderDetail.spec, 历史订单录入时未自动填充)
            ProductAttrInjector.inject(productMapper, details,
                    d -> d.getProductId(),
                    (d, v) -> d.setSpec(v),
                    p -> p.getSpec());
            // model: 写到 transient pModel (FreeMarker/myprint 通过 getModel() 读)
            ProductAttrInjector.inject(productMapper, details,
                    d -> d.getProductId(),
                    (d, v) -> d.setPModel(v),
                    p -> p.getModel());
            // colorNo: 写到 transient pColorNo (injectColorNo 是 raw type List<?>, lambda 内显式 cast)
            ProductAttrInjector.injectColorNo(productMapper, details,
                    d -> ((SalOrderDetail) d).getProductId(),
                    (d, v) -> ((SalOrderDetail) d).setPColorNo((String) v));
        }
        // v1.1.38+: 交货方式 枚举值 → 中文映射 (DELIVERY → 送货, PICKUP → 自提, DIRECT → 专车直送)
        bill.setDeliveryMethodLabel(mapDeliveryMethod(bill.getDeliveryMethod()));
        // v1.1.38+: 付款方式 枚举值 → 中文 (PREPAY → 款到发货, MONTHLY → 月结, ARRIVAL → 货到付款)
        bill.setPayTypeLabel(mapPayType(bill.getPayType()));
        // 注入客户采购订单号 (poNo 是头字段, 明细行重复显示以便打印模板)
        if (!details.isEmpty() && bill.getPoNo() != null) {
            for (SalOrderDetail d : details) {
                d.setPoNo(bill.getPoNo());
            }
        }
        Map<String, Object> model = new HashMap<>();
        model.put("bill", bill);
        model.put("details", details);
        return model;
    }

    /**
     * 交货方式枚举 → 中文.
     * 前端录入时 value="DELIVERY"/"PICKUP"/"DIRECT", 打印模板需显示中文.
     * 兼容历史脏数据 (NULL 或未识别值 → 返回空串而非抛错).
     */
    private static String mapDeliveryMethod(String code) {
        if (code == null) return "";
        switch (code) {
            case "DELIVERY": return "送货";
            case "PICKUP":   return "自提";
            case "DIRECT":   return "专车直送";
            default:         return code;  // 已是中文/未识别 → 原样透传
        }
    }

    private static String mapPayType(String code) {
        if (code == null) return "";
        switch (code) {
            case "PREPAY":  return "款到发货";
            case "MONTHLY": return "月结";
            case "ARRIVAL": return "货到付款";
            default:        return code;
        }
    }

    @Override
    public String billNo(Long billId) {
        SalOrder bill = mapper.selectById(billId);
        return bill == null ? null : bill.getBillNo();
    }
}
