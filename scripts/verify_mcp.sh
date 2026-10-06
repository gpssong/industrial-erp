#!/usr/bin/env bash
# v1.1.79: 验证 ERP MCP 端点 (POST /api/mcp) 是否与 Hermes 的 MCP client 协议一致.
# 用法:  ./verify_mcp.sh <base_url> <login_token>
# 例:    ./verify_mcp.sh https://home.93gushi.com:8088/api  <Sa-Token 值>
# 先跑这个, 3 项全过再把 ~/.hermes/config.yaml 里 mcp_servers.erp.enabled 改成 true.
set -euo pipefail
BASE="${1:?用法: verify_mcp.sh <base_url> <token>}"
TOK="${2:?需要登录 token}"
H_AUTH="Authorization: $TOK"
H_CT="Content-Type: application/json"
echo "=== 1) tools/list ==="
curl -s -X POST "$BASE/mcp" -H "$H_AUTH" -H "$H_CT" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | head -c 400
echo ""; echo "=== 2) tools/call erp_query (空 id, 应返回参数校验 isError) ==="
curl -s -X POST "$BASE/mcp" -H "$H_AUTH" -H "$H_CT" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"erp_query_maintenance_order","arguments":{"id":"x"}}}' | head -c 400
echo ""; echo "=== 3) 非白名单工具 (应拒绝) ==="
curl -s -X POST "$BASE/mcp" -H "$H_AUTH" -H "$H_CT" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"erp_delete_maintenance_order","arguments":{}}}' | head -c 400
echo ""; echo "=== 若 3 项都返回 JSON-RPC (jsonrpc/result 或 error), 协议 OK, 可在 Hermes 开 enabled ==="
