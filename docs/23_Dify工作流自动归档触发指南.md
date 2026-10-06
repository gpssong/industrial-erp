# Dify 自动归档触发 (工作流模块)

> **版本**: v1.1.79 · **最后更新**: 2026-10-06
> **适用**: ERP 工作流模块 (设备维保 / 安全检查 / 应急预案) — Dify workflow 调 ERP `POST /api/workflow/*` 自动生成草稿

---

## 1. 背景与定位

ERP 工作流模块记录三类台账: **设备维保 / 安全检查 / 应急预案**。每类都有 `POST /workflow/{maintain,safety,emergency}` 端点, 接收标准 JSON 并生成草稿 (DRAFT)。

**Dify 工作流** 是外部编排平台, 可串联 OCR / LLM / HTTP 节点, 实现「拍照 → 识别 → 生成 ERP 草稿」自动化。典型场景: 巡检员拍维保工单, Dify OCR 抽取字段, HTTP 节点调 ERP 写接口, 自动生成草稿等待人工审核。

**关键决策 (用户拍板)**:
- **永远只生成草稿, 不自动审核** — 反审核是高风险操作, 必须人工二次确认
- **鉴权用 Sa-Token cookie + login 后拿 token**, Dify 配置的 token 等同 ERP 用户身份
- **失败回写 Dify 流程节点**, 不抛错到 ERP 业务 (避免污染日志)

---

## 2. Dify 工作流设计 (推荐编排)

### 2.1 节点清单

```
[开始] → [OCR/LLM 抽取字段] → [代码节点: 校验/标准化] → [HTTP 节点: 写 ERP] → [代码节点: 提取 recordNo] → [结束]
```

| 节点 | 类型 | 作用 |
|---|---|---|
| 开始 | Start | 触发: 定时 (cron) / Webhook (工单提交) / 表单 (人工录入) |
| OCR 抽取 | LLM/OCR | 抽字段: deviceName, maintType, nextDueDate, operator 等 |
| 校验 | Code (Python) | 类型/日期/必填校验, 失败中断 |
| HTTP 写 ERP | HTTP Request | `POST /api/workflow/maintain` |
| 提取 recordNo | Code (Python) | 从响应 `code/data.msg` 提取单号 |
| 结束 | End | 输出 recordNo |

### 2.2 鉴权策略

Dify HTTP 节点支持自定义 Header。Sa-Token 配置 cookie + token 双轨:

```
Header:
  Authorization: <ERP_LOGIN_TOKEN>
  Content-Type: application/json
```

**怎么拿 token**: Dify 工作流第一个 HTTP 节点先调 ERP `/api/auth/login` 拿 token, 后续节点用模板变量 `${http_response.data.token}`。

```
POST /api/auth/login
Content-Type: application/json
Body: {"username":"dify_bot","password":"<env_var>","client":"DIFY"}
```

> ⚠️ **安全**: Dify 工作流定义里**不要明文写密码**, 用 Dify 环境变量 (DIFY_ERP_PASSWORD), 密钥只在 Dify 控制台存储。

### 2.3 各端点的最小 payload

**设备维保记录** (`POST /api/workflow/maintain`):
```json
{
  "deviceName": "8-04 注塑机",
  "maintDate": "2026-10-06",
  "maintType": "日常保养",
  "operator": "王师傅",
  "result": "正常",
  "nextDueDate": "2026-12-06",
  "details": [
    {"item": "润滑", "content": "补充润滑油", "result": "完成"},
    {"item": "清洁", "content": "擦拭机身", "result": "完成"}
  ]
}
```
权限: `work:maintain:add` (Dify bot 账号需预授)
返回值: `{"code":200,"data":null,"msg":"操作成功"}` (后端未回写 recordNo, 需 page 查询最近一条)

**安全检查记录** (`POST /api/workflow/safety`):
```json
{
  "checkDate": "2026-10-06",
  "checkType": "日常",
  "site": "二楼车间",
  "checker": "赵主管",
  "nextDueDate": "2026-11-06",
  "details": [
    {"checkItem": "灭火器", "result": "合格", "riskDesc": "", "handler": "", "fixDeadline": ""}
  ]
}
```
权限: `work:safety:add`

**应急预案** (`POST /api/workflow/emergency`):
```json
{
  "tenantId": 2101234567890123456,
  "planType": "消防",
  "scenario": "办公区烟雾报警",
  "owner": "安全员",
  "contactPhone": "13800138000",
  "drillDate": "2026-10-01",
  "nextDrillDate": "2026-12-01",
  "details": [
    {"stepNo": 1, "stepDesc": "发现火情拨打 119"}
  ]
}
```
权限: `work:emergency:add`
注: 预案必须挂租客, `tenantId` 必填

### 2.4 上传图片/附件

如果 Dify 节点要上传工单照片, 先调 `/api/system/upload/file` 拿 URL, 再写记录字段:

```
POST /api/system/upload/file
Content-Type: multipart/form-data
Body: file=@/tmp/workorder.jpg

Response: {"code":200,"data":{"url":"/upload/202610/06/abc123.jpg",...}}
```

然后把 `/upload/202610/06/abc123.jpg` 填到 `details[].riskPhoto` (检查) / `details[].stepPhoto` (预案步骤) / `drillPhotos` (预案) / `attachment` (检查整单)。

> **路径注意**: Dify HTTP 节点通过 nginx 走 `/api/...` 前缀, 文件落点 `/upload/yyyyMM/dd/xxx.jpg` 实际通过 nginx `location /upload/** → file:/opt/industrial-erp/upload/` 静态服务暴露 (SaTokenConfig 已在白名单)。

---

## 3. HTTP 节点配置 (Dify 控制台)

### 3.1 通用配置

```
Method: POST
URL:   https://home.93gushi.com:8088/api/workflow/{maintain|safety|emergency}
Headers:
  Authorization: ${http_response.login_token}
  Content-Type: application/json
Body:  ${json_object_to_string(workflow_payload)}
Timeout: 30s
Retry: 3 次 (退避 2s)
```

### 3.2 错误处理

Dify HTTP 节点失败条件:
- HTTP 状态码 ≥ 400
- 返回 JSON `code != 200`
- 超时

**建议分支**:
```
[HTTP 写 ERP 失败]
  ├─ code=401 → [重新 login 拿 token, 重试]
  ├─ msg 含 "无权限访问" → [报错给 Dify 工作流告警, 检查 Dify bot 账号 perm]
  └─ 其他 → [写入 Dify "重试队列" 节点]
```

---

## 4. ERP bot 账号配置 (一次)

```sql
-- 1) 创建 Dify bot 账号
INSERT INTO sys_user (username, password, real_name, is_admin, status, tenant_id, remark)
VALUES ('dify_bot', '<bcrypt-hash>', 'Dify 自动归档', 0, 1, 1, 'Dify 工作流自动调用, 仅 work:*:add 权限');

-- 2) 绑定 Dify 角色 (新建 DIFY_BOT 角色)
INSERT INTO sys_role (role_code, role_name, data_scope, client_scope, sort_no, status, remark)
VALUES ('DIFY_BOT', 'Dify 自动归档', 'ALL', 'PC,APP', 99, 1, 'Dify 工作流专用, 不可登录');

-- 3) 给 Dify 角色授 3 个 work:*:add perm (查询 sys_menu.id 先)
INSERT INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'PC' FROM sys_role r, sys_menu m
WHERE r.role_code='DIFY_BOT' AND m.perms IN ('work:maintain:add','work:safety:add','work:emergency:add');

-- 4) 绑定 user ↔ role
INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id FROM sys_user u, sys_role r
WHERE u.username='dify_bot' AND r.role_code='DIFY_BOT';
```

> ⚠️ **不要给 Dify bot `*:check` (审核) 权限**, 仅 add — 草稿由人工审核 (决策)

---

## 5. 失败案例与排错

| 现象 | 原因 | 排错 |
|---|---|---|
| 401 Unauthorized | token 过期或 Dify bot 无 perm | 重新 login + 检查 sys_role_menu |
| `供应商不存在` 错误 | Dify 抽取了错误 ID (类似 v1.1.79 hotfix-1 雪 ID 精度问题) | LLM 抽取后强校验 (雪花 ID 应是 19 位纯数字), 失败中断 |
| `执行成功` 但 DB 无新记录 | 表单校验失败, 后端返回 `code=500 msg="..."` | 检查 Dify HTTP 节点响应体, msg 会带字段名 |
| 草稿生成了但没有 recordNo | 后端 add 接口不返回 ID, 需要 `GET /page?pageSize=1` 拿最新一条 | Dify 加个 "查最近一条" HTTP 节点 |
| 上传图片后看不到 | `/upload/...` 路径 nginx 没暴露 | 确认 pc-web nginx.conf 有 `location /upload/**` |

---

## 6. 后续扩展 (留 v1.1.80+)

- **Dify 工作流市场模板**: 把维保/检查/预案抽成 Dify Marketplace 上的可复用 app, 其他用户 1 键导入
- **OCR 中文优化**: Agnes vision 中文精度不够, 改用 TextIn 公式/手写识别 (错题本项目已用)
- **审核建议**: 长期可以让 AI 读草稿后, 自动建议 "通过/拒绝" (但**永远不自动审**), 人在 PC/手机一键同意
- **看板集成**: v1.1.79 dashboard `/api/workflow/dashboard/upcoming` 已在 Dify HTTP 节点里可调, 巡检员定时拉取到期清单
