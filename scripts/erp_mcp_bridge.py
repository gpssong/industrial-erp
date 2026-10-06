#!/usr/bin/env python3
"""
Hermes → ERP MCP stdio 桥接脚本 (零第三方依赖, 只用标准库).

作用: Hermes 以 stdio transport 起本脚本为子进程. 本脚本:
  ① 从 stdin 读 JSON-RPC 2.0 帧 (Hermes 发 initialize / tools/list / tools/call)
  ② initialize 直接本地回 (声明 capabilities, 不走 ERP)
  ③ tools/list → 透传 ERP POST /api/mcp 的 tools/list
  ④ tools/call → 透传 ERP POST /api/mcp 的 tools/call, 把 ERP 的 result content 回给 Hermes

为什么要 stdio 桥而不是直连 ERP 的 HTTP MCP:
  Hermes 用的是官方 MCP Python SDK 的 Streamable HTTP client, 严格校验握手
  (session + initialize + 可选 SSE 流). 手写 Java Streamable HTTP 易在细节握不上.
  stdio 桥最简单: 协议由 Hermes 官方 SDK 的 stdio client 管 (稳定), 桥脚本只负责
  "JSON 转调 ERP 的裸 JSON /mcp 端点", 零新依赖, ERP 端不动.

环境变量 (Hermes 起子进程时注入, 或写在 mcp_servers.erp.env):
  ERP_MCP_BASE_URL   e.g. https://home.93gushi.com:8088/api
  ERP_MCP_TOKEN      Sa-Token (登录拿到的 bot 账号 token), 作为 Authorization 透传给 ERP

用法 (Hermes 侧 config):
  mcp_servers:
    erp:
      command: python3
      args: ["<this-file>/erp_mcp_bridge.py"]
      env: {ERP_MCP_BASE_URL: ..., ERP_MCP_TOKEN: ...}
      transport: stdio
"""
import json
import sys
import os
import urllib.request
import urllib.error

BASE_URL = os.environ.get("ERP_MCP_BASE_URL", "https://home.93gushi.com:8088/api").rstrip("/")
TOKEN = os.environ.get("ERP_MCP_TOKEN", "")

PROTOCOL_VERSION = "2024-11-05"


def write_jsonrpc(obj: dict) -> None:
    """回一帧到 stdout (JSON-RPC 2.0). Hermes 的 stdio client 按行读. """
    sys.stdout.write(json.dumps(obj) + "\n")
    sys.stdout.flush()


def rpc_error(id_, code: int, message: str) -> None:
    write_jsonrpc({"jsonrpc": "2.0", "id": id_, "error": {"code": code, "message": message}})


def call_erp(method: str, params: dict):
    """透传一次 ERP POST /api/mcp. 返回 (result_dict_or_None, error_dict_or_None)."""
    body = {"jsonrpc": "2.0", "id": "bridge", "method": method, "params": params}
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        f"{BASE_URL}/mcp",
        data=data,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "Authorization": TOKEN,
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return json.loads(raw), None
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        # ERP 返回 JSON-RPC error (401/403/业务) 或 R{code,msg} — 原样回给 Hermes
        try:
            parsed = json.loads(raw)
        except Exception:
            parsed = {"ok": False, "msg": f"HTTP {e.code}: {raw[:300]}"}
        return parsed, {"code": -32000, "message": f"ERP HTTP {e.code}"}
    except urllib.error.URLError as e:
        return None, {"code": -32001, "message": f"ERP 连接失败: {e.reason}"}
    except Exception as e:  # noqa
        return None, {"code": -32002, "message": f"ERP 调用异常: {e}"}


def handle(req: dict) -> None:
    id_ = req.get("id")
    method = req.get("method")
    params = req.get("params") or {}

    # 1) initialize: 本地直接回 (声明能力), 不走 ERP. Hermes SDK 首帧必发.
    if method == "initialize":
        write_jsonrpc({
            "jsonrpc": "2.0",
            "id": id_,
            "result": {
                "protocolVersion": PROTOCOL_VERSION,
                "serverInfo": {"name": "erp-mcp-bridge", "version": "v1.1.79"},
                "capabilities": {"tools": {}},
            },
        })
        return

    # 2) 握手通知 (initialized) — 无 id, 无回
    if method == "notifications/initialized" or (method and method.startswith("notifications/")):
        return

    # 3) tools/list → 透传 ERP
    if method == "tools/list":
        result, err = call_erp("tools/list", params)
        if err is not None:
            # ERP 出错 → 回一个空 tools, 让 Hermes 至少连上 (工具临时不可用不崩会话)
            write_jsonrpc({"jsonrpc": "2.0", "id": id_,
                           "result": {"tools": [], "_warn": str(err)}})
        else:
            # ERP 的 tools/list result 直接透传
            out = result.get("result", result) if isinstance(result, dict) else result
            write_jsonrpc({"jsonrpc": "2.0", "id": id_, "result": out})
        return

    # 4) tools/call → 透传 ERP, result content 原样回
    if method == "tools/call":
        result, err = call_erp("tools/call", params)
        if err is not None and not (isinstance(result, dict) and "result" in result):
            # 连接级错误: 包成 MCP content (isError), 让 Hermes 拿到可读 msg
            write_jsonrpc({"jsonrpc": "2.0", "id": id_, "result": {
                "content": [{"type": "text", "text": json.dumps(err, ensure_ascii=False)}],
                "isError": True,
            }})
        else:
            out = result.get("result", result) if isinstance(result, dict) else result
            write_jsonrpc({"jsonrpc": "2.0", "id": id_, "result": out})
        return

    # 5) 其它 (ping / resources 等) → method not found
    rpc_error(id_, -32601, f"bridge 不支持: {method}")


def main() -> None:
    if not TOKEN:
        sys.stderr.write("ERP_MCP_TOKEN 未设置 — 无法鉴权 ERP, 工具调用会 401\n")
    if not BASE_URL:
        sys.stderr.write("ERP_MCP_BASE_URL 未设置, 用默认 home 8088\n")
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except json.JSONDecodeError as e:
            rpc_error(None, -32700, f"parse error: {e}")
            continue
        try:
            handle(req)
        except Exception as e:  # noqa
            rpc_error(req.get("id") if isinstance(req, dict) else None, -32603, f"bridge 内部错误: {e}")


if __name__ == "__main__":
    main()
