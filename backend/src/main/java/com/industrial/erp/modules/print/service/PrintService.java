package com.industrial.erp.modules.print.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import com.industrial.erp.modules.purchase.entity.PurReceiptDetail;
import com.industrial.erp.modules.purchase.entity.PurReturn;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.entity.SalDeliveryDetail;
import com.industrial.erp.modules.sales.entity.SalReturn;
import com.industrial.erp.modules.system.entity.SysPrintTemplate;
import com.industrial.erp.modules.system.service.SysConfigService;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 打印服务 Facade。
 *
 * <p>编排 {@link PrintDataLoader} + {@link PrintTemplateEngine} + {@link PrintRenderer}
 * 完成单据渲染。Controller 只依赖本类, 不直接接触 SQL / Freemarker / 模板解析。
 *
 * <p>渲染优先级:
 * <ol>
 *   <li>DB 模板内容为 JSON 新版 `{{field}}` 配置 → {@link PrintTemplateEngine#buildFromTemplate}</li>
 *   <li>DB 模板内容为旧版勾选 JSON → {@link PrintRenderer#renderLegacyConfig}</li>
 *   <li>DB 无模板 → 读内置 .ftl → {@link PrintRenderer#renderFromString}</li>
 * </ol>
 */
@Service
public class PrintService {

    private final PrintDataLoader dataLoader;
    private final PrintTemplateEngine templateEngine;
    private final PrintRenderer renderer;
    private final SysConfigService configService;

    public PrintService(PrintDataLoader dataLoader,
                        PrintTemplateEngine templateEngine,
                        PrintRenderer renderer,
                        SysConfigService configService) {
        this.dataLoader = dataLoader;
        this.templateEngine = templateEngine;
        this.renderer = renderer;
        this.configService = configService;
    }

    // ========== 公共渲染入口 ==========

    public String renderSalesDelivery(Long id) {
        SalDelivery bill = dataLoader.findSalDelivery(id);
        if (bill == null) throw BizException.of("出库单不存在");
        List<SalDeliveryDetail> details = dataLoader.findSalDeliveryDetails(id);
        boolean taxSep = isTaxSeparation();
        return doRender("SAL_DELIVERY", taxSep, bill, details);
    }

    public String renderPurchaseReceipt(Long id) {
        PurReceipt bill = dataLoader.findPurReceipt(id);
        if (bill == null) throw BizException.of("入库单不存在");
        List<PurReceiptDetail> details = dataLoader.findPurReceiptDetails(id);
        boolean taxSep = isTaxSeparation();
        return doRender("PUR_RECEIPT", taxSep, bill, details);
    }

    public String renderPrdOrder(Long id) {
        PrdOrder bill = dataLoader.findPrdOrder(id);
        if (bill == null) throw BizException.of("生产单不存在");
        // 生产单无真实明细行，将成品信息作为一行数据传入 (用于用户自定义模板)
        // 规格/备注等字段综合 PrdOrder + BaseProduct + BOM:
        //   - spec: 优先 PrdOrder.spec (创建时快照), 缺则实时从商品表取 (避免商品更新后打印仍显示旧值)
        //   - remark: 优先 PrdOrder.remark, 缺则 PrdOrder.bomRemark
        String productSpec = dataLoader.findProductSpec(bill.getProductId());
        java.util.Map<String, Object> prdDetail = new java.util.LinkedHashMap<>();
        prdDetail.put("productName", bill.getProductName());
        prdDetail.put("productCode", bill.getProductCode());
        prdDetail.put("spec", StrUtil.isNotBlank(bill.getSpec()) ? bill.getSpec() : (productSpec != null ? productSpec : ""));
        prdDetail.put("unitName", bill.getUnitName());
        prdDetail.put("qty", bill.getPlanQty() != null ? bill.getPlanQty() : BigDecimal.ZERO);
        prdDetail.put("thickness", bill.getThickness() != null ? bill.getThickness() : "—");
        prdDetail.put("width", bill.getWidth() != null ? bill.getWidth() : "—");
        prdDetail.put("density", bill.getDensity() != null ? bill.getDensity() : "—");
        prdDetail.put("gramWeight", bill.getGramWeight() != null ? bill.getGramWeight() : "—");
        prdDetail.put("material", bill.getMaterial() != null ? bill.getMaterial() : "—");
        prdDetail.put("remark", pickRemark(bill));
        List<Object> details = java.util.Collections.singletonList(prdDetail);
        return doRender("PRD_ORDER", false, bill, details);
    }

    /** 优先生产单自身 remark, 缺则 BOM 备注, 缺则空 */
    private String pickRemark(PrdOrder bill) {
        if (bill == null) return "";
        if (StrUtil.isNotBlank(bill.getRemark())) return bill.getRemark();
        if (StrUtil.isNotBlank(bill.getBomRemark())) return bill.getBomRemark();
        return "";
    }

    public String renderPurReturn(Long id) {
        PurReturn bill = dataLoader.findPurReturn(id);
        if (bill == null) throw BizException.of("采购退货单不存在");
        boolean taxSep = isTaxSeparation();
        return doRender("PUR_RETURN", taxSep, bill, bill.getDetails() == null ? Collections.emptyList() : bill.getDetails());
    }

    public String renderSalReturn(Long id) {
        SalReturn bill = dataLoader.findSalReturn(id);
        if (bill == null) throw BizException.of("销售退货单不存在");
        boolean taxSep = isTaxSeparation();
        return doRender("SAL_RETURN", taxSep, bill, bill.getDetails() == null ? Collections.emptyList() : bill.getDetails());
    }

    // ========== 模板分发 ==========

    private String doRender(String templateType, boolean taxSep, Object bill, List<?> details) {
        SysPrintTemplate tpl = templateEngine.getDefaultTemplate(templateType);
        String content = tpl != null ? tpl.getContent() : null;

        // 1. 新版 JSON 模板 (含 {{}} 语法)
        if (StrUtil.isNotBlank(content) && templateEngine.isJsonTemplate(content)) {
            ObjectNode cfg = templateEngine.parseJsonConfig(content);
            if (cfg.has("template")) {
                return templateEngine.buildFromTemplate(cfg.get("template").asText(), taxSep, bill, details, cfg);
            }
            // 旧版勾选 JSON
            return renderer.renderLegacyConfig(cfg, taxSep, bill, details);
        }
        // 2. DB 无模板 / 模板非 JSON → 用内置 .ftl 兜底
        String tplStr = StrUtil.isNotBlank(content) ? content : readBuiltinTemplate(templateType);
        Map<String, Object> model = renderer.buildModel(bill, details, taxSep);
        return renderer.renderFromString(tplStr, model);
    }

    private String readBuiltinTemplate(String templateType) {
        String name = switch (templateType) {
            case "SAL_DELIVERY" -> "sales_delivery.ftl";
            case "PUR_RECEIPT" -> "purchase_receipt.ftl";
            case "PRD_ORDER" -> "prd_order.ftl";
            case "PUR_RETURN" -> "purchase_return.ftl";
            case "SAL_RETURN" -> "sales_return.ftl";
            default -> throw BizException.of("未知模板类型: " + templateType);
        };
        return templateEngine.readBuiltinTemplate(name);
    }

    private boolean isTaxSeparation() {
        return "true".equals(configService.getByKey("PRICE_TAX_SEPARATION"));
    }

    /** 仅供内部调用, 兼容旧测试; 不推荐新代码使用 */
    @Deprecated
    Map<String, Object> buildModel(Object bill, Object details, boolean taxSeparation) {
        return new HashMap<>(renderer.buildModel(bill, details, taxSeparation));
    }
}
