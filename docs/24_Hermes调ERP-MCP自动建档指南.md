# 24. Hermes → ERP MCP 自动建档 (设备维保工单)

> **版本**: v1.1.79 · **最后更新**: 2026-10-06
> **链路**: 手机拍照 → 发 Telegram/钉钉 → **Hermes** (fnOS gateway) → 多模态 LLM 识别 → **ERP MCP 工具** (`POST /api/mcp`) → 生成维保草稿 → 回传工单号 + PC 人工审核

本文是 [23_Dify工作流自动归档触发指南](./23_Dify工作流自动归档触发指南.md) 的**升级版** — 用 Hermes (agent 框架) 替换 Dify, 支持多模态理解 (非纯 OCR) + Skill 沉淀 + Human-in-loop 审批。

---

## 1. 链路总览

```
[车间员工手机拍照]
        │  Telegram / 钉钉 发图 + 一句话 (设备号/故障/地点)
        ▼
[Hermes gateway  @  fnOS 192.168.0.32:8087]
   │  ① vision (MiniMax-M3 首选, Agnes 3.0-flash 兜底) 读图 → 结构化字段
   │  ② 「维保工单」Skill 规范化 (上报人/设备号/故障描述/图片URL/时间)
   │  ③ 调 MCP 工具 erp_create_maintenance_order
        ▼
[ERP MCP 端点  POST /api/mcp  (JSON-RPC 2.0)]
   │  tools/call → McpToolRegistry (白名单)
   │  → WfMaintainRecordService.add()  (requirePerm work:maintain:add)
   │  → 写 wf_maintain_record (DRAFT) + sys_oper_log ([MCP] 审计)
        ▼
[生成 DRAFT 维保记录]
        │
        ├─► 回传工单号 / 链接给员工 (Hermes 再发一条消息)
        ├─► (可选) Human-in-loop: 通知维修负责人 PC 审核
        └─► 记忆沉淀: Hermes 提炼本单业务规则, 后续同类更快/更省 token
```

---

## 2. 安全设计 (工厂必开)

| # | 控制点 | 实现 |
|---|---|---|
| 1 | **高危操作人工审批** | MCP 只生成 DRAFT, **绝不自动审核**。审核走 PC `POST /workflow/maintain/{id}/check`, 人工点确认。Hermes 可发消息通知负责人, 但写入留给人。 |
| 2 | **MCP 工具白名单** | `McpToolRegistry` 只开放 `erp_create_maintenance_order` + `erp_query_maintenance_order`。**check/uncheck/delete/update 一律不暴露**。白名单外的工具名 `tools/call` 直接拒绝 (见 §5 测试)。 |
| 3 | **全量操作日志** | 每次 MCP 调用写 `sys_oper_log`, `method` 带 `[MCP]` 前缀, `businessType=MCP` — 审计查询 `SELECT ... WHERE module='MCP工具调用'` 一眼区分 "Hermes 触发" vs "PC 手工"。满足安监留痕。 |
| 4 | **鉴权双收敛** | `/mcp` 不在 SaToken 白名单 → 必须带登录 token; 且 `service.add()` 内部 `requirePerm("work:maintain:add")` → bot 账号没这 perm 直接 403。MCP 崩了也不会越权。 |

> **推荐**: 给 Hermes 单开一个 **只授 `work:maintain:add` + `work:maintain:list`、不授 check/delete 的 bot 账号** (SQL 见 [23号文档 §4](./23_Dify工作流自动归档触发指南.md#4-erp-bot-账号配置-一次)), 把权限边界落在 ERP RBAC, 比在 Hermes 侧限制更可靠。

---

## 3. Hermes 侧配置 (一次)

### 3.1 vision provider — M3 首选 + Agnes 兜底

`~/.hermes/config.yaml`:

```yaml
auxiliary:
  vision:
    provider: custom                       # 直连自定义 OpenAI 兼容端点
    model: MiniMax-M3                      # 多模态, 首选
    base_url: https://api.minimaxi.com/v1
    api_key: sk-cp-...                     # 你的 MiniMax key
    timeout: 120
custom_providers:
- name: minimax
  ...
  models:
    MiniMax-M3: {name: MiniMax-M3}         # 多模态 vision 首选
- name: agnes
  ...
  models:
    agnes-3.0-flash: {name: agnes-3.0-flash}  # vision 兜底 (M3 欠费 402 时自动降级)
```

**为什么 M3 + Agnes 双保险**: Hermes vision 的 auto 模式自带 "402/额度耗尽自动切下一个 provider"。M3 欠费时, 视觉自动落到 `agnes-3.0-flash` (已实测能读图: 从测试工单图准确读出 `设备编号 WM-804`)。

> ⚠️ M3 目前 `token plan 用量到顶 (HTTP 402)`, 需先到 MiniMax 控制台补积分/升级套餐。补费前视觉会自动走 Agnes。

### 3.2 MCP server — 接 ERP (stdio 桥接)

Hermes 的 HTTP MCP client 走官方 SDK 的 **Streamable HTTP**(严格校验 session/initialize/SSE 握手),ERP 端点是**裸 JSON-RPC 单 POST**,两者帧格式不一致。最稳的接法是 **stdio 桥接**:Hermes 用官方 stdio client(协议稳定)起一个零依赖 Python 桥脚本,桥脚本转调 ERP 的 `POST /api/mcp`,把结果按 MCP 帧回给 Hermes。

```yaml
mcp_servers:
  erp:
    enabled: false                 # 验证通过后改 true, 重启 gateway
    transport: stdio
    command: /usr/bin/python3
    args:
      - "/Users/tongban/Documents/根据前端开发erp 2/erp-system/scripts/erp_mcp_bridge.py"
    env:
      ERP_MCP_BASE_URL: "https://home.93gushi.com:8088/api"
      ERP_MCP_TOKEN: "${ERP_MCP_TOKEN}"   # 放 ~/.hermes/.env
    tools:
      default_enabled:
        - erp_create_maintenance_order
        - erp_query_maintenance_order
```

- **`scripts/erp_mcp_bridge.py`**: 零第三方依赖(只用 `json`/`urllib`/`sys`),stdin 读 JSON-RPC 帧 → `initialize` 本地回 capabilities → `tools/list`/`tools/call` 透传 ERP `/mcp`(带 `Authorization` token)→ result 原样回 stdout。已本地用 mock 端点全链路验证通过。
- **`ERP_MCP_TOKEN`**: bot 账号登录 `POST /auth/login` 拿到的 Sa-Token, 存 `~/.hermes/.env`。

### 3.3 「维保工单」Skill 雏形

`~/.hermes/skills/erp_maintain_order.md` (示意):

```markdown
# 维保工单
触发: 收到一张设备故障/维保工单照片 + 文字说明.
步骤:
1. vision 读图 → 提取 {设备编号, 故障现象, 地点, 日期, 上报人}
2. 规范化: 设备编号 缺失则向用户追问 (不瞎填)
3. 调 MCP 工具 erp_create_maintenance_order:
   {deviceName, maintDate, maintType, operator, result, remark, nextDueDate?}
4. 拿到 recordNo + id → 回传员工: "已生成维保草稿 {recordNo}, 待审核"
5. (可选) 通知维修负责人
禁止: 调用 check/uncheck/delete — 审核永远留 PC 人工.
```

---

## 4. ERP 侧实现 (v1.1.79 已含)

| 文件 | 作用 |
|---|---|
| `backend/.../modules/mcp/McpController.java` | `POST /mcp` JSON-RPC 2.0 (`tools/list` / `tools/call` / `initialize`) |
| `backend/.../modules/mcp/McpToolRegistry.java` | 工具白名单 + 执行逻辑 (只 add + query, 复用 `WfMaintainRecordService`) |
| `backend/.../modules/mcp/McpAuditService.java` | 每次调用写 `sys_oper_log` (`[MCP]` 前缀) |

- 建单走 `service.add()` → 内部 `requirePerm("work:maintain:add")` + `@OperLog` (PC 手工那套审计) + `McpAuditService` 再补一条 MCP 专属日志 (双份留痕)。
- 查单走 `service.detail(id)` (雪花 ID)。

---

## 5. 部署 + 验证 (按顺序)

1. **ERP 后端 v1.1.79 (含 `/mcp`) 双站部署** — 见 [CLAUDE.md](../CLAUDE.md) 顶部 v1.1.79 段 + `sql/50`。
2. **给 bot 账号授 `work:maintain:add` + `work:maintain:list`** (SQL 见 23号 §4, 把 DIFY_BOT 换成 HERMES_BOT)。
3. **登录拿 token**:
   ```bash
   TOK=$(curl -s -X POST https://home.93gushi.com:8088/api/auth/login \
        -H 'Content-Type: application/json' \
        -d '{"username":"hermes_bot","password":"***","client":"MCP"}' | jq -r .data.token)
   ```
4. **直接验证 ERP 端点** (确认 `/mcp` 三个方法都通):
   ```bash
   cd erp-system
   ./scripts/verify_mcp.sh https://home.93gushi.com:8088/api "$TOK"
   ```
   - ① `tools/list` 返回 2 个工具
   - ② `tools/call` 建单/查单 → 正确 result
   - ③ 非白名单工具 (`erp_delete_...`) → 拒绝
5. **验证 stdio 桥脚本能透传** (不依赖 Hermes, 直接喂帧):
   ```bash
   ERP_MCP_BASE_URL=https://home.93gushi.com:8088/api ERP_MCP_TOKEN="$TOK" \
     bash -c 'echo "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}" | python3 scripts/erp_mcp_bridge.py'
   ```
   返回 `tools` 数组即桥 OK。
6. **把 token 写进 Hermes, 开 MCP**:
   ```bash
   echo 'ERP_MCP_TOKEN='"$TOK" >> ~/.hermes/.env
   # 改 ~/.hermes/config.yaml mcp_servers.erp.enabled: true
   hermes gateway restart   # 或 hermes mcp reload
   ```
7. **E2E**: 手机发工单图 → Hermes vision 识别 → 调 `erp_create_maintenance_order` → 建草稿 → PC `sys_oper_log` 查 `module='MCP工具调用' AND method LIKE '[MCP]%'` 确认留痕。

---

## 6. 排错

| 现象 | 原因 | 处理 |
|---|---|---|
| 桥脚本报 `ERP 连接失败` | `ERP_MCP_BASE_URL` 不对 / ERP 没起 / 网络不通 | 查 base_url + 双站 8088 存活 |
| 401 / 403 | token 过期 / bot 无 `work:maintain:add` | 重新 login + 检查 `sys_role_menu` |
| 视觉 402 | MiniMax M3 欠费 | 补积分; 期间自动降级 Agnes |
| 建单 `recordNo` 空 | `service.add()` 未在 return 前 set | 已由 registry 兜底 (add 后 r 上有号) |
| 日志没 `[MCP]` 前缀 | `McpAuditService` 没注入 | 确认 Spring 扫到 `modules/mcp` 包 |

---

## 7. 后续扩展 (留 v1.1.80+)

- **安全检查 / 应急预案** 也包成 MCP 工具 (`erp_create_safety_order` / `erp_create_emergency_plan`), 同一套白名单 + 审计。
- **Human-in-loop 审批**: Hermes 建草稿后发确认消息给维修负责人, 负责人回 "同意" → Hermes 调 `check` (需给 bot 补 `work:maintain:check` 且限单人, 或走 PC 点)。
- **图片回传**: 建单前先把照片传 `/api/system/upload/file` 拿 URL, 填进 `remark`/新加的 `attachment` 列, 工单带图。
- **钉钉版**: Hermes 无原生钉钉 channel 时, 用钉钉自定义机器人 webhook 收图 → 转发到 Hermes Telegram bot (或钉钉 Stream 模式 SDK)。
