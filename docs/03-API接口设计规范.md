# API 接口设计规范

> **版本**：v1.0  
> **风格**：RESTful + JSON  
> **鉴权**：Sa-Token（`satoken` Header / Cookie）  
> **统一前缀**：`/api`

---

## 1. 统一返回 `R<T>`

```json
{
  "code": 200,
  "msg": "ok",
  "data": { ... },
  "traceId": "abc123",
  "timestamp": 1718500000000
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| code | int | 200 成功 / 400 业务错误 / 401 未登录 / 403 无权限 / 429 限流 / 500 系统错误 |
| msg | string | 中文提示 |
| data | object | 业务数据 |
| traceId | string | 全链路追踪 ID |
| timestamp | long | 服务端时间 |

**分页返回** `R<PageResult<T>>`：
```json
{
  "code": 200,
  "data": {
    "total": 1234,
    "list": [ ... ],
    "pageNum": 1,
    "pageSize": 20,
    "sumRow": { "amount": 12345.67 }
  }
}
```

---

## 2. 路径规范

```
/api/{module}/{resource}[/{id}][/{action}]
```

例：
- `GET    /api/system/user/page`
- `POST   /api/system/user`
- `PUT    /api/system/user/{id}`
- `DELETE /api/system/user/{id}`
- `POST   /api/system/user/{id}/reset-password`
- `POST   /api/purchase/order/{id}/audit`
- `POST   /api/purchase/order/{id}/unaudit`
- `GET    /api/purchase/order/export`

---

## 3. 请求规范

### 3.1 公共 Header
| Header | 必填 | 说明 |
|--------|------|------|
| `satoken` | 是 | Sa-Token 登录凭证 |
| `tenant-id` | 否 | 多租户预留 |
| `Content-Type` | POST/PUT 必填 | `application/json` |
| `X-Trace-Id` | 否 | 链路追踪 ID |

### 3.2 公共 Query / Body
- `pageNum` 默认 1
- `pageSize` 默认 20，最大 200
- `keyword` 通用模糊
- `orderBy` 例 `create_time desc,id desc`
- `startTime` / `endTime` ISO8601

### 3.3 业务参数
- 金额保留 4 位小数
- 时间 ISO8601：`2026-06-16T10:00:00`
- 枚举用整数（前端用字典翻译）

---

## 4. 模块接口清单

### 4.1 认证 `/api/auth`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` | 登录 |
| POST | `/api/auth/logout` | 登出 |
| GET  | `/api/auth/captcha` | 验证码 |
| GET  | `/api/auth/info` | 当前用户信息 + 权限 |
| POST | `/api/auth/change-password` | 改密 |

### 4.2 系统管理 `/api/system`

| 模块 | 路径前缀 | 接口 |
|------|-----------|------|
| 用户 | `/api/system/user` | page / get / save / update / delete / reset-password / change-status |
| 角色 | `/api/system/role` | page / listAll / get / save / update / delete / assign-menu / assign-dept |
| 菜单 | `/api/system/menu` | listTree / get / save / update / delete |
| 部门 | `/api/system/dept` | listTree / get / save / update / delete |
| 字典 | `/api/system/dict` | type-page / type-save / data-page / data-save / listByType |
| 配置 | `/api/system/config` | page / save / update / delete / getByKey |
| 登录日志 | `/api/system/log-login` | page / delete / clean |
| 操作日志 | `/api/system/log-operation` | page / delete / clean |
| 打印模板 | `/api/system/print-template` | page / get / save / delete / preview |
| 编号规则 | `/api/system/id-rule` | page / save / preview |
| 备份 | `/api/system/backup` | list / create / download / restore / delete |

### 4.3 基础资料 `/api/base`

| 模块 | 路径前缀 |
|------|-----------|
| 商品 | `/api/base/product` + `/api/base/product-unit` + `/api/base/product-batch` |
| 客户 | `/api/base/customer` + `/api/base/customer-price` |
| 供应商 | `/api/base/supplier` + `/api/base/supplier-price` |
| 仓库 | `/api/base/warehouse` + `/api/base/warehouse-area` + `/api/base/warehouse-location` |
| 税率 | `/api/base/tax-rate` |
| 价格等级 | `/api/base/price-level` |
| 单位 | `/api/base/unit` |

### 4.4 采购 `/api/purchase`
- `order` `receipt` `return` `inquiry` `payment` `reconcile`
- 每个资源：`page / get / save / update / delete / audit / unaudit / export`

### 4.5 销售 `/api/sales`
- `order` `delivery` `return` `quote` `receipt` `reconcile`
- `POST /api/sales/order/check-credit` 开单前信用额度校验

### 4.6 库存 `/api/inventory`

| 接口 | 说明 |
|------|------|
| `/api/inventory/stock/page` | 库存查询（多条件） |
| `/api/inventory/stock/summary` | 库存汇总（按商品/仓库） |
| `/api/inventory/stock/ledger` | 库存台账 |
| `/api/inventory/stock/transaction` | 库存流水 |
| `/api/inventory/transfer` | 调拨单 |
| `/api/inventory/move` | 移位单 |
| `/api/inventory/check` | 盘点单 |
| `/api/inventory/profit-loss` | 盈亏单 |
| `/api/inventory/warning/list` | 库存预警 |
| `/api/inventory/split` | 分切 / 复卷 |
| `/api/inventory/calc-m-per-kg` | 计算米重 |

### 4.7 生产 `/api/production`

| 接口 | 说明 |
|------|------|
| `/api/production/bom/tree` | BOM 树 |
| `/api/production/bom/save` | 保存 |
| `/api/production/order` | 加工单 CRUD + 审核 + 反审核 |
| `/api/production/order/{id}/pick` | 下推领料单 |
| `/api/production/order/{id}/finish` | 下推成品入库 |
| `/api/production/pick` | 领料单 |
| `/api/production/feed` | 补料单 |
| `/api/production/return` | 退料单 |
| `/api/production/finish` | 成品入库 |
| `/api/production/process` | 工序记录 |
| `/api/production/cost/{orderId}` | 成本归集 |

### 4.8 委外 `/api/outsource`
- `outsource-order` `outsource-receipt` `processing-fee` `supplier`

### 4.9 财务 `/api/finance`

| 接口 | 说明 |
|------|------|
| `/api/finance/receivable/page` | 应收列表 |
| `/api/finance/payable/page` | 应付列表 |
| `/api/finance/receipt` | 收款单 |
| `/api/finance/payment` | 付款单 |
| `/api/finance/transfer` | 转账单 |
| `/api/finance/reconcile` | 对账单 |
| `/api/finance/profit` | 毛利分析 |
| `/api/finance/customer-balance` | 客户余额 |
| `/api/finance/supplier-balance` | 供应商余额 |

### 4.10 报表 `/api/report`

| 接口 | 说明 |
|------|------|
| `/api/report/dashboard` | 看板数据 |
| `/api/report/sales-detail` | 销售明细 |
| `/api/report/sales-rank` | 销售排行 |
| `/api/report/sales-summary` | 销售汇总 |
| `/api/report/inventory-detail` | 库存明细 |
| `/api/report/inventory-summary` | 库存汇总 |
| `/api/report/stagnant` | 呆滞库存 |
| `/api/report/arap` | 应收应付台账 |
| `/api/report/cash-flow` | 收支明细 |
| `/api/report/operation` | 经营报表 |
| `/api/report/bigscreen` | 数据大屏 |

### 4.11 打印与导出 `/api/print`
- `GET /api/print/{billType}/{billId}` 返回 PDF / HTML
- `GET /api/print/template/{code}` 取模板
- `POST /api/print/template` 保存模板

---

## 5. 关键业务接口示例

### 5.1 登录

**请求**：
```http
POST /api/auth/login
Content-Type: application/json
{
  "username": "admin",
  "password": "123456",
  "captcha": "a3b2",
  "captchaId": "uuid-xxx"
}
```

**响应**：
```json
{
  "code": 200,
  "data": {
    "token": "satoken-xxx",
    "user": { "id":1, "name":"管理员", "deptId":1 }
  }
}
```

### 5.2 采购入库保存

```http
POST /api/purchase/receipt
{
  "billDate": "2026-06-16",
  "supplierId": 1,
  "warehouseId": 1,
  "paymentType": 2,
  "items": [
    {
      "productId": 1001,
      "qty": 100,
      "unitId": 3,
      "unitPrice": 12.5,
      "taxRate": 13,
      "batchNo": "B20260616001",
      "productionDate": "2026-06-01",
      "expiryDate": "2027-06-01"
    }
  ],
  "remark": ""
}
```

**返回**：
```json
{
  "code": 200,
  "data": { "id": 10001, "billNo": "RK202606160001" }
}
```

### 5.3 销售出库（带信用校验）

```http
POST /api/sales/delivery
{
  "customerId": 2001,
  "items": [
    { "productId": 1001, "qty": 50, "unitId": 3, "unitPrice": 18 }
  ]
}
```

**后端流程**：
1. 校验客户信用额度
2. 加 Redis 分布式锁（按 product+warehouse）
3. 检查可用库存
4. 计算成本（移动加权平均）
5. 写 `sal_delivery` + `sal_delivery_item`
6. 写 `inv_transaction`（出库流水）
7. 更新 `inv_stock` / `inv_stock_batch`
8. 生成 `fin_receivable`
9. 提交事务，删除 Redis 缓存

### 5.4 库存查询

```http
GET /api/inventory/stock/page?keyword=薄膜&warehouseId=1&pageNum=1&pageSize=20&orderBy=qty desc
```

**返回**：
```json
{
  "code": 200,
  "data": {
    "total": 156,
    "list": [
      { "productId":1001, "productCode":"FP-001", "productName":"PE保护膜", "qty":500.0, "availableQty":480.0, "lockedQty":20.0, "avgCost":12.5, "totalCost":6250 }
    ],
    "sumRow": { "qty": 12345, "totalCost": 567890 }
  }
}
```

### 5.5 BOM 展开

```http
POST /api/production/bom/expand
{
  "bomId": 1,
  "qty": 100
}
```

**返回**：
```json
{
  "code": 200,
  "data": {
    "materials": [
      { "materialId":2001, "name":"PE原料", "qty":120, "lossRate":0.05 },
      { "materialId":2002, "name":"胶水", "qty":5, "lossRate":0.02 }
    ]
  }
}
```

---

## 6. 错误码

| 码 | 含义 |
|----|------|
| 200 | 成功 |
| 400 | 业务错误（参数、库存不足、信用不足等） |
| 401 | 未登录或 token 过期 |
| 403 | 无权限 |
| 404 | 资源不存在 |
| 429 | 限流 |
| 500 | 系统异常 |
| 1001 | 库存不足 |
| 1002 | 信用超限 |
| 1003 | 单据已审，无法修改 |
| 1004 | 编号已存在 |
| 1005 | 批次重复 |
| 1101 | 文件上传失败 |
| 1201 | 打印失败 |

---

## 7. 全局异常

后端统一用 `@RestControllerAdvice` 捕获：
- `BizException` → `400`
- `NotLoginException` → `401`
- `NotPermissionException` → `403`
- `NotRoleException` → `403`
- `MethodArgumentNotValidException` → `400`
- `MaxUploadSizeExceededException` → `400`
- `Exception` 兜底 → `500`，记录日志 + 报警

---

## 8. 安全要求

- 所有接口强制 Sa-Token 鉴权（除登录、验证码、公开接口）
- 敏感操作（审核、过账、付款）二次校验密码
- 限流：`@RateLimiter(qps=10)` 注解
- 防重放：timestamp + nonce + sign
- 审计：高危操作记录 `sys_log_operation`
