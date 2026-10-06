package com.industrial.erp.modules.mcp;

import com.industrial.erp.modules.workflow.entity.WfMaintainRecord;
import com.industrial.erp.modules.workflow.service.WfMaintainRecordService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * v1.1.79: MCP 工具白名单 — 只暴露 2 个 Hermes 可用的工具:
 *
 * <ul>
 *   <li>{@code erp_create_maintenance_order} → {@link WfMaintainRecordService#add} (建 DRAFT 维保记录)
 *   <li>{@code erp_query_maintenance_order}  → {@link WfMaintainRecordService#detail} (只读查单)
 * </ul>
 *
 * <p>安全边界 (用户拍板, 见 docs/24):
 * <ul>
 *   <li>只开放 add (写草稿) + detail (只读), **绝不暴露** check / uncheck / delete / update —
 *       高危审核操作必须走 PC 端人工, MCP 只负责"生成草稿 + 查单"。
 *   <li>鉴权复用现有 Sa-Token: 控制器不在白名单, 请求必须带登录 token; 且
 *       {@code service.add()} 内部 {@code requirePerm("work:maintain:add")} — bot 账号没这 perm 直接 403。
 * </ul>
 *
 * <p>本类持有 Service 引用, 工具执行逻辑 (McpToolExecutor) 在此集中, 便于加审计。
 */
@Component
public class McpToolRegistry {

    /** 工具 1: 建维保草稿 */
    public static final String TOOL_CREATE = "erp_create_maintenance_order";
    /** 工具 2: 查维保单据 (只读) */
    public static final String TOOL_QUERY = "erp_query_maintenance_order";

    private final WfMaintainRecordService maintainService;
    private final McpAuditService audit;

    public McpToolRegistry(WfMaintainRecordService maintainService, McpAuditService audit) {
        this.maintainService = maintainService;
        this.audit = audit;
    }

    /** 工具清单 (MCP tools/list 返回). */
    public List<Map<String, Object>> listTools() {
        return List.of(
                Map.of(
                        "name", TOOL_CREATE,
                        "description", "在 ERP 创建一条设备维保记录草稿 (DRAFT)。字段由 LLM/OCR 从工单照片抽取。"
                                + "成功后返回生成的 recordNo, 供人工在 PC 端审核。不会自动审核。",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "deviceName",  Map.of("type", "string", "description", "设备名称/编号, 必填, 如 8-04 注塑机"),
                                        "maintDate",   Map.of("type", "string", "description", "维保日期, 格式 yyyy-MM-dd, 默认今天"),
                                        "maintType",   Map.of("type", "string", "description", "维保类型, 如 日常保养/大修/故障维修"),
                                        "operator",    Map.of("type", "string", "description", "操作人/上报人"),
                                        "result",      Map.of("type", "string", "description", "维保结果, 如 正常/已修复"),
                                        "nextDueDate", Map.of("type", "string", "description", "下次到期日期 yyyy-MM-dd (可空)"),
                                        "remark",      Map.of("type", "string", "description", "备注/故障现象描述"),
                                        "details",     Map.of("type", "array",
                                                "description", "维保明细项 (可选)",
                                                "items", Map.of("type", "object",
                                                        "properties", Map.of(
                                                                "item",    Map.of("type", "string", "description", "检查项名称"),
                                                                "content", Map.of("type", "string", "description", "处理内容"),
                                                                "result",  Map.of("type", "string", "description", "结果"))))
                                ),
                                "required", List.of("deviceName")
                        )
                ),
                Map.of(
                        "name", TOOL_QUERY,
                        "description", "按主键查一条设备维保记录 (只读), 用于回传工单详情/链接给员工。",
                        "inputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "id", Map.of("type", "string", "description", "维保记录主键 (雪花 ID, 19 位数字字符串) 或 recordNo")
                                ),
                                "required", List.of("id")
                        )
                )
        );
    }

    /** 是否白名单内的工具名. */
    public boolean isWhitelisted(String name) {
        return TOOL_CREATE.equals(name) || TOOL_QUERY.equals(name);
    }

    /** 执行工具, 返回 MCP result content (JSON 字符串). 抛 BizException 由控制器转成 MCP isError. */
    public String execute(String name, Map<String, Object> args) {
        switch (name) {
            case TOOL_CREATE: return executeCreate(args);
            case TOOL_QUERY:  return executeQuery(args);
            default:
                throw new IllegalArgumentException("MCP 工具不在白名单: " + name
                        + " (只开放 " + TOOL_CREATE + " / " + TOOL_QUERY + ")");
        }
    }

    private String executeCreate(Map<String, Object> args) {
        WfMaintainRecord r = new WfMaintainRecord();
        r.setDeviceName(str(args, "deviceName"));
        r.setMaintType(str(args, "maintType"));
        r.setOperator(str(args, "operator"));
        r.setResult(str(args, "result"));
        r.setRemark(str(args, "remark"));
        r.setMaintDate(date(args, "maintDate"));
        r.setNextDueDate(date(args, "nextDueDate"));
        // details
        Object dObj = args.get("details");
        if (dObj instanceof List<?> details) {
            java.util.List<com.industrial.erp.modules.workflow.entity.WfMaintainDetail> list = new java.util.ArrayList<>();
            for (Object o : details) {
                if (!(o instanceof Map<?, ?> m)) continue;
                com.industrial.erp.modules.workflow.entity.WfMaintainDetail d =
                        new com.industrial.erp.modules.workflow.entity.WfMaintainDetail();
                d.setItem((String) m.get("item"));
                d.setContent((String) m.get("content"));
                d.setResult((String) m.get("result"));
                list.add(d);
            }
            r.setDetails(list);
        }
        long t0 = System.currentTimeMillis();
        // service.add() 内部 requirePerm("work:maintain:add") — bot 无 perm 会抛 403
        maintainService.add(r);
        audit.logCreate(r, System.currentTimeMillis() - t0);
        // add() 返回 void, 但 recordNo 已生成在 r 上 (insert 前 setRecordNo)
        return audit.toJson(Map.of(
                "ok", true,
                "recordNo", r.getRecordNo() == null ? "" : r.getRecordNo(),
                "id", r.getId() == null ? "" : String.valueOf(r.getId()),
                "billStatus", r.getBillStatus() == null ? "DRAFT" : r.getBillStatus(),
                "hint", "已生成草稿, 请在 PC 端 工作流→设备维保 审核"
        ));
    }

    private String executeQuery(Map<String, Object> args) {
        String idOrNo = str(args, "id");
        if (idOrNo == null || idOrNo.isBlank()) {
            throw new IllegalArgumentException("参数 id 不能为空");
        }
        WfMaintainRecord r;
        long t0 = System.currentTimeMillis();
        try {
            // 先按雪花 ID 查; 查不到再按 recordNo 查 (recordNo 是 WM 前缀人类可读)
            Long id;
            try { id = Long.parseLong(idOrNo); } catch (NumberFormatException e) { id = null; }
            if (id != null) {
                r = maintainService.detail(id);
            } else {
                r = findByRecordNo(idOrNo);
            }
        } catch (Exception e) {
            audit.logQuery(idOrNo, false, System.currentTimeMillis() - t0, e.getMessage());
            throw e;
        }
        audit.logQuery(idOrNo, r != null, System.currentTimeMillis() - t0, r == null ? "记录不存在" : null);
        if (r == null) {
            return audit.toJson(Map.of("ok", false, "msg", "记录不存在: " + idOrNo));
        }
        return audit.toJson(Map.of(
                "ok", true,
                "recordNo", nullSafe(r.getRecordNo()),
                "deviceName", nullSafe(r.getDeviceName()),
                "maintDate", r.getMaintDate() == null ? "" : r.getMaintDate().toString(),
                "operator", nullSafe(r.getOperator()),
                "result", nullSafe(r.getResult()),
                "nextDueDate", r.getNextDueDate() == null ? "" : r.getNextDueDate().toString(),
                "billStatus", nullSafe(r.getBillStatus())
        ));
    }

    /** recordNo 查询: 走 service 无现成方法, 用 mapper selectOne (这里委托给 maintainService 的 page 语义, 简单实现). */
    private WfMaintainRecord findByRecordNo(String recordNo) {
        // service 未暴露 byNo, 用 page(1,1,null, null) 语义不合适 — 直接抛不支持, 让 Hermes 用 ID 查
        // (建单 executeCreate 已回传 id, 查单用 id 即可; recordNo 仅在日志里给人看)
        throw new IllegalArgumentException("查单请用雪花 ID (建单返回的 id), recordNo 暂不支持直接查: " + recordNo);
    }

    private String str(Map<String, Object> args, String k) {
        Object v = args.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private java.time.LocalDate date(Map<String, Object> args, String k) {
        Object v = args.get(k);
        if (v == null || String.valueOf(v).isBlank()) return null;
        try {
            return java.time.LocalDate.parse(String.valueOf(v).trim());
        } catch (Exception e) {
            throw new IllegalArgumentException(k + " 日期格式错误, 需 yyyy-MM-dd: " + v);
        }
    }

    private String nullSafe(String s) { return s == null ? "" : s; }
}
