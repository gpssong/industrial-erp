package com.industrial.erp.modules.ai.agent;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.industrial.erp.modules.ai.client.LlmClient;
import com.industrial.erp.modules.ai.rag.RagService;
import com.industrial.erp.modules.ai.replenish.ReplenishService;
import com.industrial.erp.modules.base.entity.BaseCustomer;
import com.industrial.erp.modules.base.entity.BaseProduct;
import com.industrial.erp.modules.base.entity.BaseSupplier;
import com.industrial.erp.modules.base.entity.BaseWarehouse;
import com.industrial.erp.modules.base.mapper.BaseCustomerMapper;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseSupplierMapper;
import com.industrial.erp.modules.base.mapper.BaseWarehouseMapper;
import com.industrial.erp.modules.inventory.mapper.InvStockPageQueryMapper;
import com.industrial.erp.modules.production.entity.PrdFinishedIn;
import com.industrial.erp.modules.production.service.PrdFinishedInService;
import com.industrial.erp.modules.report.service.SalesSummaryService;
import com.industrial.erp.modules.sales.entity.SalDelivery;
import com.industrial.erp.modules.sales.entity.SalOrder;
import com.industrial.erp.modules.sales.service.SalDeliveryService;
import com.industrial.erp.modules.sales.service.SalOrderService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.1.75 任务2 — ERP 只读工具注册表 (agent 的"手").
 *
 * <p>设计红线:
 * <ul>
 *   <li><b>只读</b>: 只暴露 SELECT 类查询 (库存/客户/产品/供应商/仓库/出库单/成品入库单), agent 无法直接改数据。</li>
 *   <li><b>写类不走这里</b>: 增删改/审核不进注册表; agent 只能"建议"写操作,
 *       由前端弹确认框 → 用户点确认后走真实写端点 (见 {@code AiAgentController})。
 *       v1.1.143 起白名单含成品入账 (建草稿单 + 审核入库), 依然只由前端确认后执行。</li>
 * </ul>
 *
 * <p>每个工具 = OpenAI function schema (喂 LlmClient.chatWithTools) + {@link #execute} 里的 case。
 * 新增只读工具: {@link #tools()}/{@link #toolsEnabled} 各加一行 schema + {@link #execute} 加一个 case。
 */
@Component
public class ErpToolRegistry {

    private final InvStockPageQueryMapper stockPageQueryMapper;
    private final BaseCustomerMapper customerMapper;
    private final BaseProductMapper productMapper;
    private final BaseSupplierMapper supplierMapper;
    /** v1.1.143: 成品入库提议需要真实仓库 id, 给模型一个只读仓库查询工具. */
    private final BaseWarehouseMapper warehouseMapper;
    private final SalDeliveryService salDeliveryService;
    private final SalOrderService salOrderService;
    /** v1.1.143: 只读查成品入库单 (拿到草稿 id 才能提议审核). */
    private final PrdFinishedInService finishedInService;
    /** v1.1.144: 按客户/时间段查销售汇总 (唯一口径出口, 同时供表格展示与 CSV 下载). */
    private final SalesSummaryService salesSummaryService;
    private final RagService ragService;
    private final ReplenishService replenishService;

    public ErpToolRegistry(InvStockPageQueryMapper stockPageQueryMapper,
                           BaseCustomerMapper customerMapper,
                           BaseProductMapper productMapper,
                           BaseSupplierMapper supplierMapper,
                           BaseWarehouseMapper warehouseMapper,
                           SalDeliveryService salDeliveryService,
                           SalOrderService salOrderService,
                           PrdFinishedInService finishedInService,
                           SalesSummaryService salesSummaryService,
                           RagService ragService,
                           ReplenishService replenishService) {
        this.stockPageQueryMapper = stockPageQueryMapper;
        this.customerMapper = customerMapper;
        this.productMapper = productMapper;
        this.supplierMapper = supplierMapper;
        this.warehouseMapper = warehouseMapper;
        this.salDeliveryService = salDeliveryService;
        this.salOrderService = salOrderService;
        this.finishedInService = finishedInService;
        this.salesSummaryService = salesSummaryService;
        this.ragService = ragService;
        this.replenishService = replenishService;
    }

    /** 供 LLM 的 OpenAI tools 数组. */
    public JSONArray tools() {
        JSONArray arr = new JSONArray();
        arr.add(fn("queryStock",
                "查询商品当前库存数量 (可按关键字/仓库过滤)。keyword=商品名/编码模糊, warehouseId=仓库 id (可选, 不传查全部)。返回库存行列表。",
                map("keyword", "string", "商品名或编码关键字", true,
                    "warehouseId", "number", "仓库 id, 不传查全部仓库", false)));
        arr.add(fn("listCustomers",
                "查询客户列表。keyword=客户名模糊 (可选), limit=最多条数 (默认 20)。",
                map("keyword", "string", "客户名关键字", false,
                    "limit", "number", "最多返回条数", false)));
        arr.add(fn("listProducts",
                "查询产品/商品列表。keyword=产品名/编码模糊 (可选), limit=最多条数 (默认 20)。",
                map("keyword", "string", "产品名或编码关键字", false,
                    "limit", "number", "最多返回条数", false)));
        arr.add(fn("listSuppliers",
                "查询供应商列表 (补货/采购入库需要供应商 id)。keyword=供应商名模糊 (可选), limit=最多条数 (默认 20)。返回含 id/supplierName/taxRate 等。",
                map("keyword", "string", "供应商名关键字", false,
                    "limit", "number", "最多返回条数", false)));
        arr.add(fn("getDeliveryBill",
                "按单号查一张销售出库单 (状态/客户/金额/明细行)。billNo=出库单号 (必填)。",
                map("billNo", "string", "销售出库单号", true)));
        arr.add(fn("getOrderByBillNo",
                "按单号查一张销售订单 (状态/客户/金额/明细行, 含每行 productId/qty/price)。billNo=订单号 (必填)。用于补货/采购入库前先确认订单明细。",
                map("billNo", "string", "销售订单号", true)));
        // v1.1.143: 成品入账 (AI 提议 addPrdFinishedIn/checkPrdFinishedIn) 所需只读查询
        arr.add(warehouseToolSchema());
        arr.add(finishedInToolSchema());
        // v1.1.144: 按客户/时间段查销售汇总 (结果同时作为可下载表格透传给前端)
        arr.add(salesSummaryToolSchema());
        // v1.1.75 任务3: RAG 文档问答 + 补货预测
        arr.add(fn("searchDocs",
                "在 ERP 使用手册/部署文档/业务说明里检索关键词, 回答 这个功能怎么用/流程怎么走 类问题。keyword=问题或关键词 (必填), limit=最多片段数 (默认 5)。",
                map("keyword", "string", "检索关键词或问题", true,
                    "limit", "number", "最多返回片段数", false)));
        arr.add(fn("replenishSuggest",
                "补货预测: 基于近30天出库流水 + 当前库存 + 安全库存, 给出每个商品的日均消耗/预计可售天数/是否建议补货/建议补货量。keyword=商品名或编码过滤 (可选, 不传则给全部), limit=返回条数 (默认 10)。",
                map("keyword", "string", "商品名或编码过滤, 可选", false,
                    "limit", "number", "返回条数", false)));
        return arr;
    }

    // ===== v1.1.117 — 按能力组裁剪工具 (系统设置「模型接口配置」3 能力开关真控制) =====
    /** 能力组常量 (对应 AiProperties.Caps). */
    public static final String GROUP_BUSINESS = "business";
    public static final String GROUP_QUALITY = "quality";

    /**
     * v1.1.144 — 返回体本身就是一张「可在对话里展示、可下载成 CSV 的表」的只读工具名.
     *
     * <p>模型给的文本答案靠不住 (它可能自己拼一张 markdown 表, 数字还会抄错), 所以这些工具
     * 的原始返回体由 {@code AgentService} 直接透传给前端渲染, 用户看到的就是后端算出来的数。
     */
    public static final java.util.Set<String> TABLE_TOOLS = java.util.Set.of("getSalesSummary");

    /**
     * 按启用能力组裁剪工具集 (v1.1.117).
     * <ul>
     *   <li>{@code business} (经营数据问答) 开 → 放开 7 个经营只读工具 (库存/客户/产品/供应商/出库单/订单/补货)。</li>
     *   <li>{@code quality} (质量与XK辅助) 开 → 放开 {@code searchDocs} (RAG 文档检索)。</li>
     * </ul>
     * 全关 → 返回空数组 (agent 退化为纯 LLM 文本问答, 不喂工具)。
     *
     * @param groups 启用的组名集合 (可空; null/空 = 全关)
     */
    public JSONArray toolsEnabled(java.util.Collection<String> groups) {
        JSONArray arr = new JSONArray();
        boolean biz = groups != null && groups.contains(GROUP_BUSINESS);
        boolean quality = groups != null && groups.contains(GROUP_QUALITY);
        if (biz) {
            arr.add(fn("queryStock",
                    "查询商品当前库存数量 (可按关键字/仓库过滤)。keyword=商品名/编码模糊, warehouseId=仓库 id (可选, 不传查全部)。返回库存行列表。",
                    map("keyword", "string", "商品名或编码关键字", true,
                        "warehouseId", "number", "仓库 id, 不传查全部仓库", false)));
            arr.add(fn("listCustomers",
                    "查询客户列表。keyword=客户名模糊 (可选), limit=最多条数 (默认 20)。",
                    map("keyword", "string", "客户名关键字", false,
                        "limit", "number", "最多返回条数", false)));
            arr.add(fn("listProducts",
                    "查询产品/商品列表。keyword=产品名/编码模糊 (可选), limit=最多条数 (默认 20)。",
                    map("keyword", "string", "产品名或编码关键字", false,
                        "limit", "number", "最多返回条数", false)));
            arr.add(fn("listSuppliers",
                    "查询供应商列表 (补货/采购入库需要供应商 id)。keyword=供应商名模糊 (可选), limit=最多条数 (默认 20)。返回含 id/supplierName/taxRate 等。",
                    map("keyword", "string", "供应商名关键字", false,
                        "limit", "number", "最多返回条数", false)));
            arr.add(fn("getDeliveryBill",
                    "按单号查一张销售出库单 (状态/客户/金额/明细行)。billNo=出库单号 (必填)。",
                    map("billNo", "string", "销售出库单号", true)));
            arr.add(fn("getOrderByBillNo",
                    "按单号查一张销售订单 (状态/客户/金额/明细行, 含每行 productId/qty/price)。billNo=订单号 (必填)。用于补货/采购入库前先确认订单明细。",
                    map("billNo", "string", "销售订单号", true)));
            arr.add(fn("replenishSuggest",
                    "补货预测: 基于近30天出库流水 + 当前库存 + 安全库存, 给出每个商品的日均消耗/预计可售天数/是否建议补货/建议补货量。keyword=商品名或编码过滤 (可选, 不传则给全部), limit=返回条数 (默认 10)。",
                    map("keyword", "string", "商品名或编码过滤, 可选", false,
                        "limit", "number", "返回条数", false)));
            // v1.1.143: 成品入账需要真实 仓库 id / 入库单 id, 与 tools() 同款
            arr.add(warehouseToolSchema());
            arr.add(finishedInToolSchema());
            // v1.1.144: 销售汇总 (客户 + 时间段), 与 tools() 同款
            arr.add(salesSummaryToolSchema());
        }
        if (quality) {
            arr.add(fn("searchDocs",
                    "在 ERP 使用手册/部署文档/业务说明里检索关键词, 回答 这个功能怎么用/流程怎么走 类问题。keyword=问题或关键词 (必填), limit=最多片段数 (默认 5)。",
                    map("keyword", "string", "检索关键词或问题", true,
                        "limit", "number", "最多返回片段数", false)));
        }
        return arr;
    }

    /** 执行一次工具调用, 返回 JSON 文本 (喂回模型). 只读; 失败返回结构化错误. */
    public String execute(LlmClient.ToolCall call) {
        JSONObject args = call.arguments();
        try {
            switch (call.name) {
                case "queryStock": {
                    String kw = args.getStr("keyword", "");
                    Long whId = args.get("warehouseId") == null ? null : args.getLong("warehouseId");
                    IPage<java.util.Map<String, Object>> page =
                            stockPageQueryMapper.selectStockPage(new Page<>(1, 50), kw, whId);
                    JSONObject out = new JSONObject();
                    out.set("rows", page.getRecords());
                    out.set("count", page.getRecords().size());
                    return out.toString();
                }
                case "listCustomers": {
                    String kw = args.getStr("keyword", "");
                    int limit = args.getInt("limit", 20);
                    LambdaQueryWrapper<BaseCustomer> w = new LambdaQueryWrapper<>();
                    if (kw != null && !kw.trim().isEmpty()) w.like(BaseCustomer::getCustomerName, kw);
                    w.last("LIMIT " + Math.min(limit, 50));
                    return JSONLite.list(customerMapper.selectList(w));
                }
                case "listProducts": {
                    String kw = args.getStr("keyword", "");
                    int limit = args.getInt("limit", 20);
                    LambdaQueryWrapper<BaseProduct> w = new LambdaQueryWrapper<>();
                    if (kw != null && !kw.trim().isEmpty()) {
                        w.like(BaseProduct::getProductName, kw)
                         .or().like(BaseProduct::getProductCode, kw);
                    }
                    w.last("LIMIT " + Math.min(limit, 50));
                    return JSONLite.list(productMapper.selectList(w));
                }
                case "listSuppliers": {
                    String kw = args.getStr("keyword", "");
                    int limit = args.getInt("limit", 20);
                    LambdaQueryWrapper<BaseSupplier> w = new LambdaQueryWrapper<>();
                    w.eq(BaseSupplier::getStatus, 1);
                    if (kw != null && !kw.trim().isEmpty()) w.like(BaseSupplier::getSupplierName, kw);
                    w.last("LIMIT " + Math.min(limit, 50));
                    return JSONLite.list(supplierMapper.selectList(w));
                }
                case "getDeliveryBill": {
                    String billNo = args.getStr("billNo", "");
                    SalDelivery d = firstByBillNo(billNo);
                    if (d == null) return "{\"error\":\"未找到出库单 " + billNo + "\"}";
                    return JSONLite.obj(salDeliveryService.detail(d.getId()));
                }
                case "getOrderByBillNo": {
                    String bno = args.getStr("billNo", "");
                    if (bno == null || bno.trim().isEmpty()) return "{\"error\":\"billNo 不能为空\"}";
                    IPage<SalOrder> p = salOrderService.page(1, 1, bno, null, null);
                    List<SalOrder> recs = p == null ? null : p.getRecords();
                    if (recs == null || recs.isEmpty()) return "{\"error\":\"未找到订单 " + bno + "\"}";
                    SalOrder o = salOrderService.detail(recs.get(0).getId());
                    return JSONLite.obj(o);
                }
                case "listWarehouses": {
                    String kw = args.getStr("keyword", "");
                    LambdaQueryWrapper<BaseWarehouse> w = new LambdaQueryWrapper<>();
                    w.eq(BaseWarehouse::getStatus, 1);
                    if (kw != null && !kw.trim().isEmpty()) w.like(BaseWarehouse::getWarehouseName, kw);
                    w.last("LIMIT 30");
                    return JSONLite.list(warehouseMapper.selectList(w));
                }
                case "listFinishedInBills": {
                    String kw = args.getStr("keyword", "");
                    int limit = args.getInt("limit", 10);
                    IPage<PrdFinishedIn> p = finishedInService.page(1, Math.min(limit, 30),
                            kw == null || kw.trim().isEmpty() ? null : kw);
                    return JSONLite.list(p == null ? null : p.getRecords());
                }
                case "searchDocs": {
                    String kw = args.getStr("keyword", "");
                    int limit = args.getInt("limit", 5);
                    if (!ragService.enabled()) return "{\"error\":\"RAG 未启用 (未配置 ERP_RAG_CORPUS_DIR 或语料为空), 只能查 ERP 实时数据\"}";
                    List<RagService.Chunk> hits = ragService.search(kw, Math.min(limit, 10));
                    JSONObject out = new JSONObject();
                    out.set("hits", hits);
                    out.set("count", hits.size());
                    return out.toString();
                }
                case "replenishSuggest": {
                    String kw = args.getStr("keyword", "");
                    int limit = args.getInt("limit", 10);
                    List<ReplenishService.Suggestion> recs =
                            replenishService.suggest(kw == null || kw.trim().isEmpty() ? null : kw, Math.min(limit, 30));
                    return JSONLite.list(recs);
                }
                case "getSalesSummary": {
                    SalesSummaryService.Table table = salesSummaryService.query(
                            args.getStr("startDate", ""), args.getStr("endDate", ""),
                            args.getStr("customer", ""), args.getStr("groupBy", ""),
                            args.getInt("limit", SalesSummaryService.DEFAULT_LIMIT));
                    return salesSummaryService.toToolJson(table);
                }
                default:
                    return "{\"error\":\"未知工具: " + call.name + "\"}";
            }
        } catch (Exception e) {
            return "{\"error\":\"工具执行失败: " + e.getMessage() + "\"}";
        }
    }

    /** 按单号取第一张出库单 (只读, 复用现有分页查询). */
    private SalDelivery firstByBillNo(String billNo) {
        if (billNo == null || billNo.trim().isEmpty()) return null;
        IPage<SalDelivery> p = salDeliveryService.page(1, 1, billNo, null, null, null, null);
        List<SalDelivery> recs = p == null ? null : p.getRecords();
        return (recs != null && !recs.isEmpty()) ? recs.get(0) : null;
    }

    // ---------- schema builders ----------
    /** v1.1.143 — 仓库查询 (成品入库提议必须带真实 warehouseId, 不能让模型臆造). */
    private JSONObject warehouseToolSchema() {
        return fn("listWarehouses",
                "查询仓库列表 (成品入库/领料需要仓库 id)。keyword=仓库名模糊 (可选)。"
                        + "返回含 id/warehouseCode/warehouseName/warehouseType/isDefault (1=默认仓), 只返回启用中的仓库。",
                map("keyword", "string", "仓库名关键字", false));
    }

    /** v1.1.143 — 成品入库单查询 (拿到草稿 id 才能提议审核). */
    private JSONObject finishedInToolSchema() {
        return fn("listFinishedInBills",
                "查询成品入库单 (生产入库的单据)。keyword=入库单号模糊 (可选, 不传按最新在前返回), limit=最多条数 (默认 10)。"
                        + "返回含 id/billNo/billStatus(DRAFT=草稿, CHECKED=已审核)/productId/productName/qty/unitName/warehouseId/batchNo/price。"
                        + "提议审核成品入库单前必须先调本工具拿真实 id。",
                map("keyword", "string", "入库单号关键字", false,
                    "limit", "number", "最多返回条数", false));
    }

    /** v1.1.144 — 销售汇总 (按客户/时间段), 返回体本身就是一张可展示/下载的表. */
    private JSONObject salesSummaryToolSchema() {
        return fn("getSalesSummary",
                "销售汇总查询: 按客户和时间段统计已审核/完成的销售出库单 (金额=含税销售额, 草稿单不计入)。"
                        + "customer=客户名关键字 (可选, 模糊匹配, 如「长晟」; 不传=全部客户), "
                        + "startDate/endDate=起止日期 YYYY-MM-DD (必填, 用户说「9月」就换算成本年 2026-09-01 ~ 2026-09-30; 也可直接写 2026-09 会展开成整月), "
                        + "groupBy=customer(默认, 整段每客户一行) 或 month (每客户每月一行, 用于「各月/按月」类问题), "
                        + "limit=最多行数 (默认 100)。"
                        + "返回含 title/columns/rows/totals/fileName, rows 里每行是 客户/出库单数/数量/销售额(元)。"
                        + "回答「某客户某段时间一共卖了多少」「哪个客户卖得最多」这类问题【必须】用本工具, 不要说没有销售汇总接口。",
                map("startDate", "string", "开始日期 YYYY-MM-DD (或 YYYY-MM)", true,
                    "endDate", "string", "结束日期 YYYY-MM-DD (或 YYYY-MM)", true,
                    "customer", "string", "客户名关键字, 模糊匹配, 可选", false,
                    "groupBy", "string", "customer 或 month, 默认 customer", false,
                    "limit", "number", "最多返回行数, 默认 100, 上限 300", false));
    }

    private static JSONObject fn(String name, String desc, JSONObject params) {
        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        JSONObject f = new JSONObject();
        f.put("name", name);
        f.put("description", desc);
        f.put("parameters", params);
        tool.put("function", f);
        return tool;
    }

    /** 参数定义 (OpenAI JSON Schema object). 键值交替: (name, type, desc, required?) × N. */
    private static JSONObject map(Object... kv) {
        JSONObject schema = new JSONObject();
        schema.put("type", "object");
        JSONObject props = new JSONObject();
        List<String> required = new ArrayList<>();
        for (int i = 0; i + 3 < kv.length; i += 4) {
            String name = (String) kv[i];
            String type = (String) kv[i + 1];
            String desc = (String) kv[i + 2];
            boolean req = Boolean.TRUE.equals(kv[i + 3]);
            JSONObject p = new JSONObject();
            p.put("type", type);
            p.put("description", desc);
            props.put(name, p);
            if (req) required.add(name);
        }
        schema.put("properties", props);
        if (!required.isEmpty()) schema.put("required", required);
        return schema;
    }
}
