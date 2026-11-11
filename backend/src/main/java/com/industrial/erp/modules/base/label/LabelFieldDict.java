package com.industrial.erp.modules.base.label;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 每个 bizType 的合法 field 名单 (docs/32 §3.2)。
 *
 * <p>**保存闸**和 PC 模板管理页的字段下拉**共读这一处** —— 名单写两遍必然漂移。
 * 主数据字段（producerName / producerAddress / xkNo / execStandard / storageCondition / inspectorCode）
 * 由 LabelContextLoader 统一并进上下文，所以这里也一并列为合法字段。
 */
public final class LabelFieldDict {

    private LabelFieldDict() {}

    public static final List<String> MASTER_FIELDS = List.of(
            "producerName", "producerAddress", "xkNo", "execStandard", "storageCondition", "inspectorCode");

    private static final Map<String, List<String>> BIZ_FIELDS = Map.of(
            "FG_ROLL", List.of("productName", "productCode", "spec", "unitName", "qty", "batchNo",
                    "productionDate", "orderNo", "billNo", "gramWeight", "width", "thickness", "colorNo"),
            "RAW_PALLET", List.of("productName", "productCode", "supplierName", "batchNo", "qty",
                    "unitName", "receiptBillNo", "receiptDate", "inspectionReportNo"),
            "CARTON", List.of("customerName", "deliveryAddress", "deliveryPhone", "productName",
                    "spec", "qty", "unitName", "orderNo", "deliveryBillNo", "deliveryDate", "batchNo"),
            "CERT", List.of("productName", "productCode", "spec", "material", "barcode", "batchNo",
                    "productionDate", "qty", "unitName", "shelfLifeDays", "inspectionBillNo",
                    "inspectionDate", "orderNo"),
            "GOODS_LABEL", List.of("productName", "productCode", "spec", "material", "unitName",
                    "gramWeight", "width", "thickness", "colorNo", "barcode", "qrcode", "shelfLifeDays"));

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("productName", "产品名称"), Map.entry("productCode", "商品编码"),
            Map.entry("spec", "规格"), Map.entry("unitName", "单位"), Map.entry("qty", "数量"),
            Map.entry("batchNo", "批号"), Map.entry("productionDate", "生产日期"),
            Map.entry("orderNo", "生产单号"), Map.entry("billNo", "入库单号"),
            Map.entry("gramWeight", "克重(g/个)"), Map.entry("width", "幅宽(mm)"),
            Map.entry("thickness", "厚度(mm)"), Map.entry("colorNo", "色号"),
            Map.entry("supplierName", "供应商"), Map.entry("receiptBillNo", "采购入库单号"),
            Map.entry("receiptDate", "入库日期"), Map.entry("inspectionReportNo", "检验报告号"),
            Map.entry("customerName", "客户名称"), Map.entry("deliveryAddress", "收货地址"),
            Map.entry("deliveryPhone", "联系电话"), Map.entry("deliveryBillNo", "销售出库单号"),
            Map.entry("deliveryDate", "出库日期"),
            Map.entry("material", "材质"), Map.entry("barcode", "条码"),
            Map.entry("qrcode", "二维码内容"), Map.entry("shelfLifeDays", "保质期(天)"),
            Map.entry("inspectionBillNo", "检验单号"), Map.entry("inspectionDate", "检验日期"),
            Map.entry("producerName", "生产者名称"), Map.entry("producerAddress", "生产者地址"),
            Map.entry("xkNo", "生产许可证编号"), Map.entry("execStandard", "执行标准"),
            Map.entry("storageCondition", "贮存条件"), Map.entry("inspectorCode", "检验员代号"));

    /**
     * 枚举 {@link LabelBizType#of} 大小写不敏感，字典必须同样宽容 —— 否则 {@code fg_roll}
     * 会先被枚举放过、再被这张表判成「每个业务字段都不合法」，保存闸报的错还指向字段而不是大小写。
     */
    private static String key(String bizType) {
        return bizType == null ? "" : bizType.toUpperCase(Locale.ROOT);
    }

    public static List<String> fields(String bizType) {
        List<String> biz = BIZ_FIELDS.getOrDefault(key(bizType), List.of());
        return java.util.stream.Stream.concat(biz.stream(), MASTER_FIELDS.stream()).toList();
    }

    public static boolean isKnown(String bizType, String field) {
        return fields(bizType).contains(field);
    }

    public static String label(String field) { return LABELS.getOrDefault(field, field); }
}
