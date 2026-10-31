package com.industrial.erp.modules.production.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.industrial.erp.common.CreateByNameInjector;
import com.industrial.erp.common.PageResult;
import com.industrial.erp.common.R;
import com.industrial.erp.modules.mes.service.MesWorkStartService;
import com.industrial.erp.modules.production.entity.PrdOrder;
import com.industrial.erp.modules.production.service.PrdOrderService;
import com.industrial.erp.modules.production.service.ProductionPdfService;
import com.industrial.erp.modules.system.mapper.SysUserMapper;
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

    private final PrdOrderService service;
    private final ProductionPdfService pdfService;
    private final SysUserMapper userMapper;
    private final MesWorkStartService workStartService;

    public PrdOrderController(PrdOrderService service, ProductionPdfService pdfService, SysUserMapper userMapper,
                              MesWorkStartService workStartService) {
        this.service = service;
        this.pdfService = pdfService;
        this.userMapper = userMapper;
        this.workStartService = workStartService;
    }

    // v1.1.104: 补全方法级 perm 拦截 (历史 v1.0 时代漏注解, v1.1.104 check-perm.py 脚本扫到)
    @SaCheckPermission(value = {"production:order:list"}, orRole = "admin")
    @GetMapping("/page")
    public R<PageResult<PrdOrder>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                        @RequestParam(defaultValue = "20") Integer pageSize,
                                        @RequestParam(required = false) String billNo,
                                        @RequestParam(required = false) String billStatus,
                                        @RequestParam(required = false) String productName) {
        PageResult<PrdOrder> pr = PageResult.of(service.page(pageNum, pageSize, billNo, billStatus, productName));
        CreateByNameInjector.inject(userMapper, pr.getRecords(), PrdOrder::getCreateBy, PrdOrder::setCreateByName);
        return R.ok(pr);
    }

    @SaCheckPermission(value = {"production:order:list"}, orRole = "admin")
    @GetMapping("/{id}")
    public R<PrdOrder> detail(@PathVariable Long id) { return R.ok(service.detail(id)); }

    @SaCheckPermission(value = {"production:order:add"}, orRole = "admin")
    @PostMapping
    public R<Long> add(@RequestBody PrdOrder o) { return R.ok(service.add(o)); }

    @SaCheckPermission(value = {"production:order:edit"}, orRole = "admin")
    @PutMapping
    public R<Void> update(@RequestBody PrdOrder o) { service.update(o); return R.ok(); }

    // v1.1.104: 开工 (release) 是高危 method (开工会触发自动领料单 + 工序展开 + 库存账), 必须有 perm
    @SaCheckPermission(value = {"production:order:release"}, orRole = "admin")
    @PostMapping("/{id}/release")
    public R<Long> release(@PathVariable Long id) { return R.ok(service.release(id)); }

    @SaCheckPermission(value = {"production:order:finish"}, orRole = "admin")
    @PostMapping("/{id}/finish")
    public R<Void> finish(@PathVariable Long id, @RequestParam BigDecimal goodQty,
                          @RequestParam BigDecimal lossQty, @RequestParam(required = false) Long warehouseId) {
        service.finish(id, goodQty, lossQty, warehouseId);
        return R.ok();
    }

    // voidOrder 复用 production:order:edit perm (业务上"作废"是"编辑"的反向操作, 不新增 perm 节点)
    @SaCheckPermission(value = {"production:order:edit"}, orRole = "admin")
    @PostMapping("/{id}/void")
    public R<Void> voidOrder(@PathVariable Long id) { service.voidOrder(id); return R.ok(); }

    @SaCheckPermission(value = {"production:order:delete"}, orRole = "admin")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) { service.delete(id); return R.ok(); }

    // ===== v1.1.132 XK16-204 开工确认 (生产环境 + 投料原料批号 + 批号照片) =====

    /**
     * 吹膜开工: 环境确认合格 → 自动出 2 份生产环境检查合格记录 → 开工.
     * v1.1.141: 工业品只需环境合格 (批号/照片选填); 食品级仍要求原料批号 + 批号照片,
     * 环境不合格一律拒绝 (整事务回滚).
     */
    @SaCheckPermission(value = {"production:order:release"}, orRole = "admin")
    @PostMapping("/{id}/blow-start")
    public R<java.util.Map<String, Object>> blowStart(@PathVariable Long id,
                                                      @RequestBody com.industrial.erp.modules.mes.dto.WorkStartConfirmDTO body) {
        return R.ok(workStartService.confirmBlowStart(id, body));
    }

    /** 制袋开工: 环境确认合格 → 2 份制袋车间环境检查记录 → 工序转进行中 + 自动领用该单可用半成品. */
    @SaCheckPermission(value = {"production:order:release", "production:report:add", "production:report:list"},
            mode = SaMode.OR, orRole = "admin")
    @PostMapping("/{id}/bag-start")
    public R<java.util.Map<String, Object>> bagStart(@PathVariable Long id,
                                                     @RequestBody com.industrial.erp.modules.mes.dto.WorkStartConfirmDTO body) {
        return R.ok(workStartService.confirmBagStart(id, body));
    }

    /** 开工确认台账回显 (批号 + 照片 + 自动生成的环境检查单号). */
    @SaCheckPermission(value = {"production:order:list", "production:report:list"},
            mode = SaMode.OR, orRole = "admin")
    @GetMapping("/{id}/work-start-records")
    public R<java.util.List<com.industrial.erp.modules.mes.entity.MesWorkStartConfirm>> workStartRecords(@PathVariable Long id) {
        return R.ok(workStartService.listByOrder(id));
    }

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
