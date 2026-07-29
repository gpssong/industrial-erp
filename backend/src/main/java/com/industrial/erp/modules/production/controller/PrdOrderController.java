package com.industrial.erp.modules.production.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.service.PrdOrderService;
import com.industrial.erp.modules.production.service.ProductionPdfService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Tag(name = "生产加工")
@RestController
@RequestMapping("/production/order")
public class PrdOrderController {

    public PrdOrderController(PrdOrderService service, ProductionPdfService pdfService) {
        this.service = service;
        this.pdfService = pdfService;
    }

    private final PrdOrderService service;
    private final ProductionPdfService pdfService;

    @GetMapping("/page")
    public R<PageResult<PrdOrder>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                        @RequestParam(defaultValue = "20") Integer pageSize,
                                        @RequestParam(required = false) String billNo,
                                        @RequestParam(required = false) String billStatus,
                                        @RequestParam(required = false) String productName) {
        return R.ok(PageResult.of(service.page(pageNum, pageSize, billNo, billStatus, productName)));
    }

    @GetMapping("/{id}")
    public R<PrdOrder> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @PostMapping
    public R<Long> add(@RequestBody PrdOrder o) { return R.ok(service.add(o)); }

    @PutMapping
    public R<Void> update(@RequestBody PrdOrder o) { service.update(o); return R.ok(); }

    @PostMapping("/{id}/release")
    public R<Long> release(@PathVariable Long id) { return R.ok(service.release(id)); }

    @PostMapping("/{id}/finish")
    public R<Void> finish(@PathVariable Long id, @RequestParam BigDecimal goodQty,
                          @RequestParam BigDecimal lossQty, @RequestParam(required = false) Long warehouseId) {
        service.finish(id, goodQty, lossQty, warehouseId);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }

    /**
     * v1.1.11+: 分享 PDF (流式输出 application/pdf)
     * <p>App 端 {@code uni.downloadFile} → {@code uni.share({type:'file'})} 调原生分享菜单.
     * <p>Sa-Token 拦截器已要求登录, 这里再校验列表权限.
     */
    @SaCheckPermission(value = {"production:order:list"}, orRole = "admin")
    @GetMapping(value = "/{id}/pdf", produces = "application/pdf;charset=UTF-8")
    public void pdf(@PathVariable Long id, HttpServletResponse response) throws IOException {
        byte[] pdf = pdfService.renderPdf(id);
        // 用 billNo 命名文件: PD202607270003.pdf
        PrdOrder order = service.detail(id);
        String filename = (order != null && order.getBillNo() != null ? order.getBillNo() : ("prd_order_" + id)) + ".pdf";
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/pdf");
        response.setContentLength(pdf.length);
        response.setHeader("Content-Disposition", "inline; filename=\"" + filename + "\"; filename*=UTF-8''" + encoded);
        try (OutputStream out = response.getOutputStream()) {
            out.write(pdf);
            out.flush();
        }
    }
}
