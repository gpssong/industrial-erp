package com.industrial.erp.modules.report.service;

/**
 * v1.1.68 回收站支持的 11 类软删单据.
 *
 * <p>每类对应一张 head 表 (+ 可选 detail 表 + 类别名来源列).
 * key 与前端 Tab / URL type 参数 / RecycleBinMapper.xml <choose> 分支一一对应.
 */
public enum RecycleType {

    PUR_ORDER("pur_order", "pur_order", "pur_order_detail", "order_id", "采购订单", "supplier_name"),
    PUR_RECEIPT("pur_receipt", "pur_receipt", "pur_receipt_detail", "receipt_id", "采购入库", "supplier_name"),
    PUR_RETURN("pur_return", "pur_return", "pur_return_detail", "return_id", "采购退货", "supplier_name"),
    SAL_ORDER("sal_order", "sal_order", "sal_order_detail", "order_id", "销售订单", "customer_name"),
    SAL_DELIVERY("sal_delivery", "sal_delivery", "sal_delivery_detail", "delivery_id", "销售出库", "customer_name"),
    SAL_RETURN("sal_return", "sal_return", "sal_return_detail", "return_id", "销售退货", "customer_name"),
    PRD_ORDER("prd_order", "prd_order", null, null, "生产加工单", "product_name"),
    PRD_REQUISITION("prd_requisition", "prd_requisition", "prd_requisition_detail", "requisition_id", "生产领料", "prd_order_no"),
    PRD_BOM("prd_bom", "prd_bom", "prd_bom_detail", "bom_id", "BOM清单", "product_name"),
    INV_CHECK("inv_check", "inv_check", "inv_check_detail", "check_id", "库存盘点", "warehouse_name"),
    INV_TRANSFER("inv_transfer", "inv_transfer", "inv_transfer_detail", "transfer_id", "库存调拨", "out_warehouse_id"),
    ;

    private final String key;
    private final String headTable;
    private final String detailTable;
    private final String detailFk;
    private final String label;
    private final String categoryCol;

    RecycleType(String key, String headTable, String detailTable, String detailFk,
                String label, String categoryCol) {
        this.key = key;
        this.headTable = headTable;
        this.detailTable = detailTable;
        this.detailFk = detailFk;
        this.label = label;
        this.categoryCol = categoryCol;
    }

    public String getKey() { return key; }
    public String getHeadTable() { return headTable; }
    public String getDetailTable() { return detailTable; }
    public String getDetailFk() { return detailFk; }
    public String getLabel() { return label; }
    public String getCategoryCol() { return categoryCol; }

    public boolean hasDetail() { return detailTable != null; }

    /** 按 key 解析; 非法 key 返回 null (调用方抛 BizException). */
    public static RecycleType fromKey(String key) {
        if (key == null) return null;
        for (RecycleType t : values()) {
            if (t.key.equals(key)) return t;
        }
        return null;
    }
}
