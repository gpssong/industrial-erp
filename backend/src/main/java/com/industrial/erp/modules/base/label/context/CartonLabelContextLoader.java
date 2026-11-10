package com.industrial.erp.modules.base.label.context;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.label.LabelBizType;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.entity.SalDeliveryDetail;
import com.industrial.erp.modules.sales.mapper.SalDeliveryDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalDeliveryMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
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
            // 日期走 printableDate()：屏幕（/bills 选单列）与纸面必须是同一个口径，所以规则只写在一处。
            m.put("deliveryDate", printableDate(s));
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

    /**
     * 示例数据：一行明细 = 一张箱标，键顺序照抄 {@link #load}。
     * deliveryDate 给的是 LocalDate，和 printableDate() 真实返回的类型一致，
     * 免得画布上"日期能显示"而真单打出来是空。
     */
    @Override
    public List<Map<String, Object>> sample() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("customerName", "示例客户");
        row.put("deliveryAddress", "示例收货地址");
        row.put("deliveryPhone", "00000000000");
        row.put("orderNo", "SO202611060001");
        row.put("deliveryBillNo", "SD202611060001");
        row.put("deliveryDate", LocalDate.of(2026, 11, 6));
        row.put("productName", "背心袋");
        row.put("spec", "30×45cm");
        row.put("unitName", "箱");
        row.put("qty", new BigDecimal("20"));
        row.put("batchNo", "PH20261106A");
        return List.of(row);
    }

    /**
     * 这张出库单在标签层该显示哪一天 —— **唯一出口**：箱标纸面与 `/base/label/bills` 的 `billDate` 都调它。
     *
     * <p>出库日期回落开单日期：库侧 `sal_delivery.delivery_date` 165/165 全 NULL（建单/审核链路都不写它），
     * 而 `bill_date` 165/165 有值。不回落 ⇒ 箱标「日期」栏在任何一张单上都是空白。
     *
     * <p>如实记录一件事：`/bills` 那列**今天没有任何界面在渲染**（PC 下拉只拼 单号+客户/品名+批号，
     * App 只显示单号）。所以规则写在这里不是为了修一个看得见的空列，是为了**哪天有人渲染它时不必再决定一遍回落**
     * —— 决定过一次并留了单测，比让下一个改前端的人重新猜便宜。
     *
     * <p>写入侧（让建单/审核真的写 `delivery_date`）**故意不动** —— 那是销售出库自己的字段口径，不属于标签层。
     * 哪天那边开始写了，这里的回落自动失效（有值就以它为准），不用改代码。
     */
    public static LocalDate printableDate(SalDelivery s) {
        return s.getDeliveryDate() != null ? s.getDeliveryDate() : s.getBillDate();
    }
}
