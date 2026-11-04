package com.industrial.erp.modules.base.label.context;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.label.LabelBizType;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import com.industrial.erp.modules.purchase.entity.PurReceiptDetail;
import com.industrial.erp.modules.purchase.mapper.PurReceiptDetailMapper;
import com.industrial.erp.modules.purchase.mapper.PurReceiptMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 原料垛标 (RAW_PALLET)：**品名/批号/数量在明细行上**，表头只有供应商/单号/报告号
 * （docs/32 §3.2）⇒ 必须按行展开，一次打印可能出 N 张。
 */
@Component
public class RawPalletLabelContextLoader implements LabelContextLoader {

    public RawPalletLabelContextLoader(PurReceiptMapper receiptMapper, PurReceiptDetailMapper detailMapper) {
        this.receiptMapper = receiptMapper;
        this.detailMapper = detailMapper;
    }

    private final PurReceiptMapper receiptMapper;
    private final PurReceiptDetailMapper detailMapper;

    @Override
    public LabelBizType bizType() { return LabelBizType.RAW_PALLET; }

    @Override
    public List<Map<String, Object>> load(Long bizId, List<Long> lineIds) {
        PurReceipt r = receiptMapper.selectById(bizId);
        if (r == null) throw BizException.of(404, "采购入库单不存在或已删除: id=" + bizId);
        List<PurReceiptDetail> details = detailMapper.selectByReceiptId(bizId);
        // Set.copyOf 遇 [null] 抛 NPE（500），先挡一道回 400；null/空 仍是"全部行"
        if (lineIds != null && lineIds.stream().anyMatch(Objects::isNull))
            throw BizException.of(400, "明细行 id 不能为空");
        Set<Long> pick = (lineIds == null || lineIds.isEmpty()) ? Set.of() : Set.copyOf(lineIds);

        List<Map<String, Object>> out = new ArrayList<>();
        for (PurReceiptDetail d : details) {
            if (!pick.isEmpty() && !pick.contains(d.getId())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("supplierName", r.getSupplierName());
            m.put("receiptBillNo", r.getBillNo());
            m.put("receiptDate", r.getBillDate());
            m.put("inspectionReportNo", r.getInspectionReportNo());
            m.put("productName", d.getProductName());
            m.put("productCode", d.getProductCode());
            m.put("unitName", d.getUnitName());
            m.put("qty", d.getQty());
            m.put("batchNo", d.getBatchNo());
            out.add(m);
        }
        if (out.isEmpty()) throw BizException.of(400, "该采购入库单没有可打印的明细行（或所选行不属于该单）");
        return out;
    }
}
