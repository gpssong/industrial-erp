package com.industrial.erp.modules.print.service;

import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.entity.SalDeliveryDetail;
import com.industrial.erp.modules.sales.mapper.SalDeliveryDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalDeliveryMapper;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import com.industrial.erp.modules.purchase.entity.PurReceiptDetail;
import com.industrial.erp.modules.purchase.mapper.PurReceiptDetailMapper;
import com.industrial.erp.modules.purchase.mapper.PurReceiptMapper;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单据打印服务
 * 模板引擎: Freemarker
 * 模板可放置: 数据库 sys_print_template.content, 或 resources/templates/print/*.ftl
 */
@Service
public class PrintService {

    public PrintService(SalDeliveryMapper deliveryMapper, SalDeliveryDetailMapper deliveryDetailMapper, PurReceiptMapper receiptMapper, PurReceiptDetailMapper receiptDetailMapper) {
        this.deliveryMapper = deliveryMapper;
        this.deliveryDetailMapper = deliveryDetailMapper;
        this.receiptMapper = receiptMapper;
        this.receiptDetailMapper = receiptDetailMapper;
    }

    private final SalDeliveryMapper deliveryMapper;
    private final SalDeliveryDetailMapper deliveryDetailMapper;
    private final PurReceiptMapper receiptMapper;
    private final PurReceiptDetailMapper receiptDetailMapper;

    /**
     * 销售出库单 HTML (用于浏览器/小票打印)
     */
    public String renderSalesDelivery(Long id) {
        SalDelivery bill = deliveryMapper.selectById(id);
        if (bill == null) throw BizException.of("出库单不存在");
        List<SalDeliveryDetail> details = deliveryDetailMapper.selectByDeliveryId(id);

        Map<String, Object> model = new HashMap<>();
        model.put("bill", bill);
        model.put("details", details);
        return render("sales_delivery.ftl", model);
    }

    /**
     * 采购入库单 HTML
     */
    public String renderPurchaseReceipt(Long id) {
        PurReceipt bill = receiptMapper.selectById(id);
        if (bill == null) throw BizException.of("入库单不存在");
        List<PurReceiptDetail> details = receiptDetailMapper.selectByReceiptId(id);

        Map<String, Object> model = new HashMap<>();
        model.put("bill", bill);
        model.put("details", details);
        return render("purchase_receipt.ftl", model);
    }

    private String render(String templateName, Object model) {
        try {
            Configuration cfg = new Configuration(Configuration.VERSION_2_3_32);
            cfg.setClassLoaderForTemplateLoading(this.getClass().getClassLoader(), "/templates/print");
            cfg.setDefaultEncoding("UTF-8");
            Template t = cfg.getTemplate(templateName);
            StringWriter sw = new StringWriter();
            t.process(model, sw);
            return sw.toString();
        } catch (IOException | TemplateException e) {
            throw BizException.of("模板渲染失败: " + e.getMessage());
        }
    }
}
