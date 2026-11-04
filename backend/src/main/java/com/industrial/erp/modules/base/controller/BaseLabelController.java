package com.industrial.erp.modules.base.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.industrial.erp.common.R;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import com.industrial.erp.modules.base.label.LabelBizType;
import com.industrial.erp.modules.base.label.LabelMaster;
import com.industrial.erp.modules.base.label.LabelMasterService;
import com.industrial.erp.modules.base.label.LabelPreviewRequest;
import com.industrial.erp.modules.base.label.LabelPreviewService;
import com.industrial.erp.modules.base.label.RenderResult;
import com.industrial.erp.modules.base.service.BaseLabelTemplateService;
import com.industrial.erp.modules.production.entity.PrdFinishedIn;
import com.industrial.erp.modules.production.mapper.PrdFinishedInMapper;
import com.industrial.erp.modules.purchase.entity.PurReceipt;
import com.industrial.erp.modules.purchase.entity.PurReceiptDetail;
import com.industrial.erp.modules.purchase.mapper.PurReceiptDetailMapper;
import com.industrial.erp.modules.purchase.mapper.PurReceiptMapper;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.entity.SalDeliveryDetail;
import com.industrial.erp.modules.sales.mapper.SalDeliveryDetailMapper;
import com.industrial.erp.modules.sales.mapper.SalDeliveryMapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "标签打印")
@RestController
@RequestMapping("/base/label")
public class BaseLabelController {

    public BaseLabelController(LabelMasterService masterService,
                               BaseLabelTemplateService templateService,
                               LabelPreviewService previewService,
                               PrdFinishedInMapper finishedInMapper,
                               PurReceiptMapper purReceiptMapper,
                               SalDeliveryMapper salDeliveryMapper,
                               PurReceiptDetailMapper purReceiptDetailMapper,
                               SalDeliveryDetailMapper salDeliveryDetailMapper) {
        this.masterService = masterService;
        this.templateService = templateService;
        this.previewService = previewService;
        this.finishedInMapper = finishedInMapper;
        this.purReceiptMapper = purReceiptMapper;
        this.salDeliveryMapper = salDeliveryMapper;
        this.purReceiptDetailMapper = purReceiptDetailMapper;
        this.salDeliveryDetailMapper = salDeliveryDetailMapper;
    }

    private final LabelMasterService masterService;
    private final BaseLabelTemplateService templateService;
    private final LabelPreviewService previewService;
    private final PrdFinishedInMapper finishedInMapper;
    private final PurReceiptMapper purReceiptMapper;
    private final SalDeliveryMapper salDeliveryMapper;
    private final PurReceiptDetailMapper purReceiptDetailMapper;
    private final SalDeliveryDetailMapper salDeliveryDetailMapper;

    /** 标签主数据（生产者名称/地址/XK 证号/执行标准/贮存条件/检验员代号）。
     *  App 打印中心也要读它，不能叫只有业务权限的人去调 /system/config/*，所以单独开一个只读口。 */
    @SaCheckPermission(value = {"base:label:list", "base:product:list"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/master")
    public R<LabelMaster> master() { return R.ok(masterService.load()); }

    /** 选单据：三类标签各查各的主表，keyword 打单号/品名/客户/供应商。
     *  只回 10 列以内的小 Map，别把整张单据喂给前端；limit 封顶 50（分页拦截器 maxLimit=200 是防 OOM 的兜底，不是许可）。 */
    @SaCheckPermission(value = {"base:label:list", "production:finished-in:list", "purchase:receipt:list",
            "sales:delivery:list"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/bills")
    public R<List<Map<String, Object>>> bills(@RequestParam String bizType,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(defaultValue = "30") int limit) {
        int n = Math.min(Math.max(limit, 1), 50);
        String kw = keyword == null ? "" : keyword.trim();
        // **A9 裁定 R2：必须是 switch 表达式，不是 switch 语句。** 原写法 `switch (...) { case ... }` 是**语句**，
        // Java 对 switch 语句**不要求穷尽** ⇒ 将来加第五类标签时一声不响走进「没有 case」，
        // `/bills` 回空列表，界面显示「没有单据」，看着像数据问题、其实是漏了分支 ——
        // 本任务本想要的那道编译器兜底（见本节末原话）在语句写法下根本不存在。改成表达式才真的顶回来。
        List<Map<String, Object>> out = switch (LabelBizType.of(bizType)) {
            case FG_ROLL -> finishedInMapper.selectList(new LambdaQueryWrapper<PrdFinishedIn>()
                            .and(!kw.isEmpty(), w -> w.like(PrdFinishedIn::getBillNo, kw)
                                    .or().like(PrdFinishedIn::getProductName, kw)
                                    .or().like(PrdFinishedIn::getBatchNo, kw))
                            .orderByDesc(PrdFinishedIn::getId).last("LIMIT " + n))
                    .stream().map(b -> row(b.getId(), b.getBillNo(), dateStr(b.getBillDate()),
                            b.getProductName(), b.getBatchNo(), b.getBillStatus())).toList();
            case RAW_PALLET -> purReceiptMapper.selectList(new LambdaQueryWrapper<PurReceipt>()
                            .and(!kw.isEmpty(), w -> w.like(PurReceipt::getBillNo, kw)
                                    .or().like(PurReceipt::getSupplierName, kw))
                            .orderByDesc(PurReceipt::getId).last("LIMIT " + n))
                    .stream().map(b -> row(b.getId(), b.getBillNo(), dateStr(b.getBillDate()),
                            b.getSupplierName(), null, b.getBillStatus())).toList();
            case CARTON -> salDeliveryMapper.selectList(new LambdaQueryWrapper<SalDelivery>()
                            .and(!kw.isEmpty(), w -> w.like(SalDelivery::getBillNo, kw)
                                    .or().like(SalDelivery::getCustomerName, kw))
                            .orderByDesc(SalDelivery::getId).last("LIMIT " + n))
                    .stream().map(b -> row(b.getId(), b.getBillNo(), dateStr(b.getDeliveryDate()),
                            b.getCustomerName(), null, b.getBillStatus())).toList();
            case CERT -> throw BizException.of(400, "合格证本期不出（docs/32 §11 第 4 条）");
        };
        return R.ok(out);
    }

    /** 选明细行：原料垛标/箱标按行出，成品卷标没有行概念 → 回空数组（前端据此隐藏行选择）。 */
    @SaCheckPermission(value = {"base:label:list", "production:finished-in:list", "purchase:receipt:list",
            "sales:delivery:list"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/lines")
    public R<List<Map<String, Object>>> lines(@RequestParam String bizType, @RequestParam Long bizId) {
        List<Map<String, Object>> out = new ArrayList<>();
        // **A9 裁定 R2（后半）：这里刻意保留 switch 语句 + default。** `default` 的含义是「这两类没有明细行」，
        // 是业务事实不是漏分支 —— 改成表达式会把 FG_ROLL/CERT 变成编译期必须逐个列出的 case，反而看不出「有意为空」。
        switch (LabelBizType.of(bizType)) {
            case RAW_PALLET -> purReceiptDetailMapper.selectByReceiptId(bizId).forEach(d ->
                    out.add(line(d.getId(), d.getProductName(), d.getQty(), d.getBatchNo())));
            case CARTON -> salDeliveryDetailMapper.selectByDeliveryId(bizId).forEach(d ->
                    out.add(line(d.getId(), d.getProductName(), d.getQty(), d.getBatchNo())));
            default -> { /* FG_ROLL / CERT 无明细行 */ }
        }
        return R.ok(out);
    }

    /** 元素级结构化预览：模板由这里解析（显式 id 走 detail，否则取该类型默认位），服务只拿模板干活 (A9 裁定 R1)。 */
    @SaCheckPermission(value = {"base:label:print", "base:label:list"}, mode = SaMode.OR, orRole = "admin")
    @PostMapping("/preview")
    public R<List<RenderResult>> preview(@RequestBody LabelPreviewRequest req) {
        BaseLabelTemplate tpl = req.getTemplateId() != null
                ? templateService.detail(req.getTemplateId())
                : templateService.defaultOf(req.getBizType());
        if (tpl == null) throw BizException.of(400, "该标签类型还没有默认模板，请先到「标签模板」建一份");
        return R.ok(previewService.preview(tpl, req.getBizId(), req.getLineIds()));
    }

    private static Map<String, Object> row(Long id, String billNo, String billDate,
                                           String mainName, String batchNo, String billStatus) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("billNo", billNo);
        m.put("billDate", billDate);
        m.put("mainName", mainName);
        m.put("batchNo", batchNo);
        m.put("billStatus", billStatus);
        return m;
    }

    private static Map<String, Object> line(Long id, String name, Object qty, String batchNo) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("qty", qty);
        m.put("batchNo", batchNo);
        return m;
    }

    /** **A9 裁定 R3：日期不许用 `String.valueOf(...)`。** `sal_delivery.delivery_date` 是可空列
     *  (`sql/04_schema_sales.sql` 里 `DATE DEFAULT NULL`)，而 `String.valueOf((Object) null)` 回的是
     *  **四个字母的 "null"** —— 选单列表直接显示「null」当日期。这和 A7 裁定 R1 是同一类错：
     *  拿占位文案当值。空日期就是空字符串，前端显示空白。 */
    private static String dateStr(java.time.LocalDate d) { return d == null ? "" : d.toString(); }
}
