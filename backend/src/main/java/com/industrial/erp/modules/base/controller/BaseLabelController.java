package com.industrial.erp.modules.base.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.industrial.erp.common.R;
import com.industrial.erp.exception.BizException;
import com.industrial.erp.modules.base.entity.BaseLabelPrintLog;
import com.industrial.erp.modules.base.entity.BaseLabelTemplate;
import com.industrial.erp.modules.base.label.FeieLabelRenderer;
import com.industrial.erp.modules.base.label.FeieResult;
import com.industrial.erp.modules.base.label.LabelBizType;
import com.industrial.erp.modules.base.label.LabelContextService;
import com.industrial.erp.modules.base.label.LabelFieldDict;
import com.industrial.erp.modules.base.label.LabelMaster;
import com.industrial.erp.modules.base.label.LabelMasterService;
import com.industrial.erp.modules.base.label.LabelPrintLogRequest;
import com.industrial.erp.modules.base.label.LabelPrintService;
import com.industrial.erp.modules.base.label.LabelPreviewRequest;
import com.industrial.erp.modules.base.label.LabelPreviewService;
import com.industrial.erp.modules.base.label.RenderResult;
import com.industrial.erp.modules.base.service.BaseLabelTemplateService;
import com.industrial.erp.modules.production.client.FeiePrintClient;
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
import com.industrial.erp.modules.system.entity.SysFeiePrinterConfig;
import com.industrial.erp.modules.system.mapper.SysFeiePrinterConfigMapper;
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
                               LabelContextService contextService,
                               LabelPrintService printService,
                               FeieLabelRenderer feieRenderer,
                               FeiePrintClient feieClient,
                               PrdFinishedInMapper finishedInMapper,
                               PurReceiptMapper purReceiptMapper,
                               SalDeliveryMapper salDeliveryMapper,
                               PurReceiptDetailMapper purReceiptDetailMapper,
                               SalDeliveryDetailMapper salDeliveryDetailMapper,
                               SysFeiePrinterConfigMapper printerConfigMapper) {
        this.masterService = masterService;
        this.templateService = templateService;
        this.previewService = previewService;
        this.contextService = contextService;
        this.printService = printService;
        this.feieRenderer = feieRenderer;
        this.feieClient = feieClient;
        this.finishedInMapper = finishedInMapper;
        this.purReceiptMapper = purReceiptMapper;
        this.salDeliveryMapper = salDeliveryMapper;
        this.purReceiptDetailMapper = purReceiptDetailMapper;
        this.salDeliveryDetailMapper = salDeliveryDetailMapper;
        this.printerConfigMapper = printerConfigMapper;
    }

    private final LabelMasterService masterService;
    private final BaseLabelTemplateService templateService;
    private final LabelPreviewService previewService;
    private final LabelContextService contextService;
    private final LabelPrintService printService;
    private final FeieLabelRenderer feieRenderer;
    private final FeiePrintClient feieClient;
    private final PrdFinishedInMapper finishedInMapper;
    private final PurReceiptMapper purReceiptMapper;
    private final SalDeliveryMapper salDeliveryMapper;
    private final PurReceiptDetailMapper purReceiptDetailMapper;
    private final SalDeliveryDetailMapper salDeliveryDetailMapper;
    private final SysFeiePrinterConfigMapper printerConfigMapper;

    /** 标签主数据（生产者名称/地址/XK 证号/执行标准/贮存条件/检验员代号）。
     *  App 打印中心也要读它，不能叫只有业务权限的人去调 /system/config/*，所以单独开一个只读口。 */
    @SaCheckPermission(value = {"base:label:list", "base:product:list"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/master")
    public R<LabelMaster> master() { return R.ok(masterService.load()); }

    /** 该标签类型可选的字段名单（模板编辑器的下拉数据源）。 */
    @SaCheckPermission(value = {"base:label:template", "base:label:list"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/fields")
    public R<List<Map<String, String>>> fields(@RequestParam String bizType) {
        LabelBizType.of(bizType); // 未知类型直接 400
        List<Map<String, String>> out = new ArrayList<>();
        for (String f : LabelFieldDict.fields(bizType)) {
            out.add(Map.of("name", f, "label", LabelFieldDict.label(f)));
        }
        return R.ok(out);
    }

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
        return R.ok(previewService.preview(tplOf(req.getBizType(), req.getTemplateId()),
                req.getBizId(), req.getLineIds()));
    }

    /** 飞鹅通道预览：直接回渲染后的指令文本，让 PC 侧看清「发出去的是什么」——含退化说明。 */
    @SaCheckPermission(value = {"base:label:print", "base:label:list"}, mode = SaMode.OR, orRole = "admin")
    @PostMapping("/feie/preview")
    public R<List<String>> feiePreview(@RequestBody LabelPreviewRequest req) {
        return R.ok(renderForFeie(req));
    }

    /** 发到飞鹅云（用当前启用打印机），逐张发送，单张失败落 FAIL 日志并继续下一张。 */
    @SaCheckPermission(value = {"base:label:print"}, mode = SaMode.OR, orRole = "admin")
    @PostMapping("/feie/print")
    public R<Map<String, Object>> feiePrint(@RequestBody LabelPreviewRequest req) {
        SysFeiePrinterConfig cfg = activePrinter();
        BaseLabelTemplate tpl = tplOf(req.getBizType(), req.getTemplateId());
        String bizNo = bizNoOf(req);   // 一张单据一个单号，循环外取一次，别每张标签回查一遍
        List<String> contents = renderForFeie(req);
        int ok = 0;
        List<String> failed = new ArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            String content = contents.get(i);
            String failMsg = null;
            try {
                // **A10-R1**：`printMsg` **业务失败不抛异常** —— 只有传输/解析失败才抛（见 `FeiePrintClient.post`
                // 的 `catch (Exception) → throw RuntimeException`）。飞鹅用 `ret == 0` 表示云端**接受了下发**，
                // 打印机脱机 / 错 UKEY / 欠费一律 HTTP 200 + `ret≠0`。只看有没有异常就等于把失败记成 SUCCESS，
                // 这张表在 XK 检查时就成了假账。判定口径抄既有链路 `FeiePrintService.java:168-181`。
                JSONObject resp = feieClient.printMsg(cfg.getUser(), cfg.getUkey(), cfg.getDeviceSn(), content, 1);
                failMsg = FeieResult.failureOf(resp);   // null ⇒ 云端已接受下发
            } catch (Exception e) {
                // `failMsg` 这一个变量同时是「有没有失败」和「失败原因」⇒ 空 message 的异常
                // （裸 `new RuntimeException()`、部分三方库）会把它留成 null，下面判空就当「云端已接受」，
                // 一张没发出去的标签就在 XK 的自证材料里成了 SUCCESS。兜到类名：文案粗一点，账不能记错。
                failMsg = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
            }
            if (failMsg == null) {
                printService.logSuccess(req.getBizType(), req.getBizId(), bizNo, tpl,
                        "FEIE", cfg.getPrinterName(), null, 1, content);
                ok++;
            } else {
                printService.logFail(req.getBizType(), req.getBizId(), bizNo, tpl,
                        "FEIE", cfg.getPrinterName(), null, 1, content, failMsg);
                failed.add("第 " + (i + 1) + " 张: " + failMsg);
            }
        }
        return R.ok(Map.of("total", contents.size(), "success", ok, "failed", failed.size(),
                "failedItems", failed,
                "note", "飞鹅是 58mm 流式小票机：坐标与分割线画不出，条码退化成明文 (docs/32 §3.1)"));
    }

    /** App 蓝牙打完之后回报日志（成功与失败都要报）。绑定关系只存手机本地，MAC 由这里上报落库。 */
    @SaCheckPermission(value = {"base:label:print"}, mode = SaMode.OR, orRole = "admin")
    @PostMapping("/print/log")
    public R<Void> logPrint(@RequestBody LabelPrintLogRequest req) {
        // **A10-R3**：这是**记账**入口，不是出标签入口 —— 模板快照取不到就留空，
        // 不许用 tplOf（它会抛）把一行打印记录拒掉。bizType 是 `NOT NULL` 列，缺了确实落不进，
        // 所以只能在这里明确报 400，而不是等 A10-R2 那个 warn 把丢行悄悄吃掉。
        if (req.getBizType() == null || req.getBizType().isBlank()) {
            throw BizException.of(400, "缺少 bizType，无法记这笔打印日志");
        }
        BaseLabelTemplate tpl = tplQuietly(req.getBizType(), req.getTemplateId());
        int copies = req.getCopies() <= 0 ? 1 : req.getCopies();   // App 没报就按 1，别落个 0 份
        if ("FAIL".equalsIgnoreCase(req.getStatus())) {
            printService.logFail(req.getBizType(), req.getBizId(), req.getBizNo(), tpl, "BT_TSPL",
                    req.getPrinterName(), req.getPrinterMac(), copies, req.getContent(), req.getErrorMsg());
        } else {
            printService.logSuccess(req.getBizType(), req.getBizId(), req.getBizNo(), tpl, "BT_TSPL",
                    req.getPrinterName(), req.getPrinterMac(), copies, req.getContent());
        }
        return R.ok();
    }

    @SaCheckPermission(value = {"base:label:list", "base:label:print"}, mode = SaMode.OR, orRole = "admin")
    @GetMapping("/logs")
    public R<List<BaseLabelPrintLog>> logs(@RequestParam(required = false) String bizType,
                                           @RequestParam(required = false) Long bizId,
                                           @RequestParam(defaultValue = "30") int limit) {
        return R.ok(printService.recent(bizType, bizId, limit));
    }

    /** 取模板：templateId 为空就用该类型的默认模板 —— 与 A9 的 /preview 完全同一条路。 */
    private BaseLabelTemplate tplOf(String bizType, Long templateId) {
        BaseLabelTemplate tpl = templateId != null ? templateService.detail(templateId)
                : templateService.defaultOf(bizType);
        if (tpl == null) throw BizException.of(400, "该标签类型还没有默认模板，请先到「标签模板」建一份");
        return tpl;
    }

    /**
     * 第五个（A10-R3）：只给 `/print/log` 用 —— 模板快照取不到就返回 null，让日志照常落。
     * 与 `tplOf` 的差别是有意的：出标签时没模板是真错误（必须 400），补记日志时 `template_id`/`template_code`
     * 只是两列快照（DDL 都允许 NULL），宁可空着也不能丢一行「谁在什么时候打错了什么」。
     */
    private BaseLabelTemplate tplQuietly(String bizType, Long templateId) {
        try {
            return templateId != null ? templateService.detail(templateId) : templateService.defaultOf(bizType);
        } catch (Exception e) {
            return null;
        }
    }

    /** 单据 → 上下文 → RenderResult（含 zxing 码图），预览/飞鹅/TSPL 三通道共用；一张明细行 = 一张标签，所以是 List。 */
    private List<RenderResult> renderResults(LabelPreviewRequest req) {
        return previewService.preview(tplOf(req.getBizType(), req.getTemplateId()),
                req.getBizId(), req.getLineIds());
    }

    private List<String> renderForFeie(LabelPreviewRequest req) {
        List<String> out = new ArrayList<>();
        for (RenderResult r : renderResults(req)) out.add(feieRenderer.render(r));
        return out;
    }

    /** 当前启用的飞鹅打印机。`deleted=0` 由实体的 `@TableLogic` 自动带上，这里不手写。
     *  文案与 `FeiePrintService.getActiveConfig()` 有意不同：标签通道要告诉用户**去哪配**。 */
    private SysFeiePrinterConfig activePrinter() {
        SysFeiePrinterConfig cfg = printerConfigMapper.selectOne(new LambdaQueryWrapper<SysFeiePrinterConfig>()
                .eq(SysFeiePrinterConfig::getStatus, 1)
                .last("LIMIT 1"));
        if (cfg == null) throw BizException.of(400, "未配置飞鹅打印机，请到「系统设置-飞鹅打印」添加");
        return cfg;
    }

    /** 日志上的单号只是给人看的：ctx 里键名按 bizType 不同，这里做一次归一（A5 的 flat 键，见 LabelFieldDict）。 */
    private String bizNoOf(LabelPreviewRequest req) {
        String key = switch (LabelBizType.of(req.getBizType())) {
            case FG_ROLL, CERT -> "billNo";
            case RAW_PALLET -> "receiptBillNo";
            case CARTON -> "deliveryBillNo";
        };
        Object v = contextService.loadFirst(req.getBizType(), req.getBizId(), req.getLineIds()).get(key);
        return v == null ? null : String.valueOf(v);
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
