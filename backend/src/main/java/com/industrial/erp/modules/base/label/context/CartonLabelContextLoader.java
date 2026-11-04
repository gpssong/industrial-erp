package com.industrial.erp.modules.base.label.context;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.label.LabelBizType;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.entity.SalDeliveryDetail;
import com.industrial.erp.modules.sales.mapper.SalDeliveryDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalDeliveryMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 箱标 (CARTON)：数据源 sal_delivery + 明细行。结构同原料垛标——按行展开，
 * 表头放客户/地址/电话/单号，行放品名/规格/数量/单位/批号。
 */
@Component
public class CartonLabelContextLoader implements LabelContextLoader {

    public CartonLabelContextLoader(SalDeliveryMapper deliveryMapper, SalDeliveryDetailMapper detailMapper) {
        this.deliveryMapper = deliveryMapper;
        this.detailMapper = detailMapper;
    }

    private final SalDeliveryMapper deliveryMapper;
    private final SalDeliveryDetailMapper detailMapper;

    @Override
    public LabelBizType bizType() { return LabelBizType.CARTON; }

    @Override
    public List<Map<String, Object>> load(Long bizId, List<Long> lineIds) {
        SalDelivery s = deliveryMapper.selectById(bizId);
        if (s == null) throw BizException.of(404, "销售出库单不存在或已删除: id=" + bizId);
        List<SalDeliveryDetail> details = detailMapper.selectByDeliveryId(bizId);
        // Set.copyOf 遇 [null] 抛 NPE（500），先挡一道回 400；null/空 仍是"全部行"
        if (lineIds != null && lineIds.stream().anyMatch(Objects::isNull))
            throw BizException.of(400, "明细行 id 不能为空");
        Set<Long> pick = (lineIds == null || lineIds.isEmpty()) ? Set.of() : Set.copyOf(lineIds);

        List<Map<String, Object>> out = new ArrayList<>();
        for (SalDeliveryDetail d : details) {
            if (!pick.isEmpty() && !pick.contains(d.getId())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("customerName", s.getCustomerName());
            m.put("deliveryAddress", s.getAddress());
            m.put("deliveryPhone", s.getPhone());
            m.put("orderNo", s.getOrderNo());
            m.put("deliveryBillNo", s.getBillNo());
            m.put("deliveryDate", s.getDeliveryDate());
            m.put("productName", d.getProductName());
            m.put("spec", d.getSpec());
            m.put("unitName", d.getUnitName());
            m.put("qty", d.getQty());
            // 审核选批次出库 (v1.1.152) 后这里可能是 "B1|B2|" —— 多个批次用 | 连接、行尾裸 | 代表无批号那部分。
            // 原样打：批号是给追溯读的，不做二次加工 (docs/32 §3.2)。
            m.put("batchNo", d.getBatchNo());
            out.add(m);
        }
        if (out.isEmpty()) throw BizException.of(400, "该销售出库单没有可打印的明细行（或所选行不属于该单）");
        return out;
    }
}
