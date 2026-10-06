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
import com.industrial.erp.modules.base.mapper.BaseCustomerMapper;
import com.industrial.erp.modules.base.mapper.BaseProductMapper;
import com.industrial.erp.modules.base.mapper.BaseSupplierMapper;
import com.industrial.erp.modules.inventory.mapper.InvStockPageQueryMapper;
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
 *   <li><b>只读</b>: 只暴露 SELECT 类查询 (库存/客户/产品/出库单), agent 无法直接改数据。</li>
 *   <li><b>写类不走这里</b>: 增删改/审核不进注册表; agent 只能"建议"写操作,
 *       由前端弹确认框 → 用户点确认后走真实写端点 (见 {@code AiAgentController})。</li>
 * </ul>
 *
 * <p>每个工具 = OpenAI function schema (喂 LlmClient.chatWithTools) + {@link #execute} 里的 case。
 * 新增只读工具: {@link #tools()} 加一行 schema + {@link #execute} 加一个 case。
 */
@Component
public class ErpToolRegistry {

    private final InvStockPageQueryMapper stockPageQueryMapper;
    private final BaseCustomerMapper customerMapper;
    private final BaseProductMapper productMapper;
    private final BaseSupplierMapper supplierMapper;
    private final SalDeliveryService salDeliveryService;
    private final SalOrderService salOrderService;
    private final RagService ragService;
    private final ReplenishService replenishService;

    public ErpToolRegistry(InvStockPageQueryMapper stockPageQueryMapper,
                           BaseCustomerMapper customerMapper,
                           BaseProductMapper productMapper,
                           BaseSupplierMapper supplierMapper,
                           SalDeliveryService salDeliveryService,
                           SalOrderService salOrderService,
                           RagService ragService,
                           ReplenishService replenishService) {
        this.stockPageQueryMapper = stockPageQueryMapper;
        this.customerMapper = customerMapper;
        this.productMapper = productMapper;
        this.supplierMapper = supplierMapper;
        this.salDeliveryService = salDeliveryService;
        this.salOrderService = salOrderService;
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
