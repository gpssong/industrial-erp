package com.industrial.erp.modules.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.industrial.erp.exception.BizException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v1.1.79: 轻量 MCP-over-HTTP server — 供 Hermes 通过 MCP 协议调 ERP。
 *
 * <p>实现 MCP 2024-11-05 协议的两个方法:
 * <ul>
 *   <li>{@code tools/list} → 返回工具白名单 (见 {@link McpToolRegistry#listTools})
 *   <li>{@code tools/call} → 执行工具, 返回 content
 * </ul>
 *
 * <p>鉴权: 本路径不在 SaToken 白名单, 请求必须带 Sa-Token 登录态 (bot 账号 token)。
 * 工具内部再 {@code requirePerm}, 双重收敛。
 *
 * <p>JSON-RPC 2.0 报文: {@code {"jsonrpc":"2.0","id":..,"method":"tools/list"|"tools/call","params":..}}
 * 响应: {@code {"jsonrpc":"2.0","id":..,"result":..}} 或 {@code {"jsonrpc":"2.0","id":..,"error":..}}
 */
@Tag(name = "MCP 工具 (Hermes)")
@RestController
@RequestMapping("/mcp")
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpToolRegistry registry;
    private final ObjectMapper om;

    public McpController(McpToolRegistry registry, ObjectMapper om) {
        this.registry = registry;
        this.om = om;
    }

    /** JSON-RPC 2.0 请求体 (只取需要的 3 字段, 其余忽略). */
    record JsonRpcRequest(String jsonrpc, Object id, String method, Map<String, Object> params) {}

    @Operation(summary = "MCP: tools/list + tools/call (JSON-RPC 2.0)")
    @PostMapping
    public Map<String, Object> handle(@RequestBody Map<String, Object> req) {
        Object id = req.get("id");
        if (id == null) id = UUID.randomUUID().toString();
        String method = String.valueOf(req.get("method"));

        Map<String, Object> params = req.get("params") instanceof Map ?
                (Map<String, Object>) req.get("params") : Map.of();

        try {
            switch (method) {
                case "tools/list":
                    return rpcOk(id, Map.of(
                            "tools", registry.listTools(),
                            "_meta", Map.of("protocolVersion", PROTOCOL_VERSION)
                    ));
                case "tools/call":
                    return toolsCall(id, params);
                case "initialize":
                    // Hermes 握手: 返回 server 能力
                    return rpcOk(id, Map.of(
                            "protocolVersion", PROTOCOL_VERSION,
                            "serverInfo", Map.of("name", "industrial-erp-mcp", "version", "v1.1.79"),
                            "capabilities", Map.of("tools", Map.of())
                    ));
                default:
                    return rpcError(id, -32601, "Method not found: " + method);
            }
        } catch (BizException be) {
            // 权限/业务错误 → MCP 层包成 isError, 让 Hermes 拿到明确 msg (不 500 崩掉)
            log.info("MCP tools/call 业务错误 method={}, id={}, err={}", method, id, be.getMessage());
            return rpcError(id, -32000, be.getMessage());
        } catch (Exception e) {
            log.warn("MCP 处理异常 method={}, id={}", method, id, e);
            return rpcError(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private Map<String, Object> toolsCall(Object id, Map<String, Object> params) {
        String name = params.get("name") == null ? "" : String.valueOf(params.get("name"));
        Map<String, Object> args = params.get("arguments") instanceof Map ?
                (Map<String, Object>) params.get("arguments") : Map.of();

        if (!registry.isWhitelisted(name)) {
            // 白名单外工具 → 返回 MCP 成功但 content 带拒绝说明 (isError=true)
            return rpcOk(id, Map.of(
                    "content", List.of(Map.of("type", "text", "text", toJsonDeny(name))),
                    "isError", true
            ));
        }

        String result;
        try {
            result = registry.execute(name, args);
        } catch (IllegalArgumentException iae) {
            // 参数/白名单错误 → content 里给人类可读 msg (Hermes 能据此纠偏)
            return rpcOk(id, Map.of(
                    "content", List.of(Map.of("type", "text", "text", iae.getMessage())),
                    "isError", true
            ));
        }
        // 正常: 单条 text content
        return rpcOk(id, Map.of(
                "content", List.of(Map.of("type", "text", "text", result)),
                "isError", false
        ));
    }

    private String toJsonDeny(String name) {
        try {
            return om.writeValueAsString(Map.of("ok", false,
                    "msg", "工具 " + name + " 不在白名单 (只开放 erp_create_maintenance_order / erp_query_maintenance_order)"));
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    private Map<String, Object> rpcOk(Object id, Map<String, Object> result) {
        return Map.of("jsonrpc", "2.0", "id", id, "result", result);
    }

    private Map<String, Object> rpcError(Object id, int code, String msg) {
        return Map.of("jsonrpc", "2.0", "id", id,
                "error", Map.of("code", code, "message", msg));
    }
}
