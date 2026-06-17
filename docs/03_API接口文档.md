# API 接口文档

## 通用说明
- Base URL: `http://localhost:8080/api`
- 认证: 除登录外, 需在 Header 加 `Authorization: <token>`
- 统一返回: `{ code: 200, msg: "ok", data: {...}, ts: 1234567890 }`
- 错误码:
  - 200 成功
  - 400 参数错误
  - 401 未登录
  - 403 无权限
  - 500 系统异常

## 一、认证

| Method | Path | 说明 |
|---|---|---|
| POST | /auth/login | 登录 (admin/admin123) |
| POST | /auth/logout | 登出 |
| GET  | /auth/me | 当前用户 (含菜单/权限) |
| GET  | /auth/captcha | 图形验证码 |

## 二、系统管理

| 模块 | 接口 |
|---|---|
| 用户 | /system/user/page, /system/user/{id}, /system/user, /system/user/{id}/resetPwd |
| 角色 | /system/role/page, /system/role/{id}, /system/role |
| 菜单 | /system/menu/list, /system/menu/mine |
| 部门 | /system/dept/list, /system/dept/tree |

## 三、基础资料

| 模块 | 接口 |
|---|---|
| 商品 | /base/product/page, /base/product/{id}, /base/product, /base/product/convert |
| 客户 | /base/customer/page, /base/customer, /base/customer/{id} |
| 供应商 | /base/supplier/page, /base/supplier, /base/supplier/{id} |
| 仓库 | /base/warehouse/list, /base/warehouse, /base/warehouse/{id} |
| 单位 | /base/unit/list, /base/unit |

## 四、采购

| 模块 | 接口 |
|---|---|
| 订单 | /purchase/order/page, /purchase/order/{id}, /purchase/order |
| 入库 | /purchase/receipt/page, /purchase/receipt/{id}, /purchase/receipt, /purchase/receipt/{id}/check |
| 退货 | /purchase/return/page |
| 询价 | /purchase/inquiry/page |
| 付款 | /purchase/payment/page |

## 五、销售

| 模块 | 接口 |
|---|---|
| 订单 | /sales/order/page, /sales/order/{id}, /sales/order |
| 出库 | /sales/delivery/page, /sales/delivery/{id}, /sales/delivery, /sales/delivery/{id}/check |
| 退货 | /sales/return/page |
| 报价 | /sales/quotation/page |
| 收款 | /sales/receipt/page |

## 六、库存 (核心)

| 模块 | 接口 |
|---|---|
| 库存查询 | /inventory/stock/page |
| 库存台账 | /inventory/ledger/page |
| 库存预警 | /inventory/warning/list |
| 调拨 | /inventory/transfer/page |
| 盘点 | /inventory/check/page |
| 盈亏 | /inventory/profit-loss/page |
| 分切/复卷 | /inventory/cut/page |

## 七、生产

| 模块 | 接口 |
|---|---|
| BOM | /production/bom/page, /production/bom/{id}, /production/bom |
| 生产单 | /production/order/page, /production/order/{id}, /production/order, /production/order/{id}/release, /production/order/{id}/finish |
| 领料 | /production/requisition/page |
| 工序 | /production/process/page |
| 成品入库 | /production/finished-in/page |

## 八、委外加工

| 模块 | 接口 |
|---|---|
| 发料 | /outsource/issue, /outsource/issue/{id}/check |
| 入库 | /outsource/pi, /outsource/pi/{id}/check |
| 加工费 | /outsource/process-fee/page |

## 九、财务往来

| 模块 | 接口 |
|---|---|
| 应收/应付 | /finance/arap/page |
| 收/付款 | /finance/arap/cash |
| 对账 | /finance/reconciliation/page |

## 十、报表

| 模块 | 接口 |
|---|---|
| 工作台 | /report/dashboard |
| 销售汇总 | /report/sales/summary?startDate&endDate |
| 销售排行 | /report/sales/ranking?startDate&endDate&limit |
| 库存汇总 | /report/inventory/summary |
| 库存账龄 | /report/inventory/aging |
| 应收应付 | /report/arap?billType=AR/AP |
| 毛利分析 | /report/profit?startDate&endDate |

## 十一、打印

| Path | 说明 |
|---|---|
| /print/sales-delivery/{id}.html | 销售出库单 (80mm 小票) |
| /print/purchase-receipt/{id}.html | 采购入库单 |

## 十二、错误处理

```json
// 业务错误 (库存不足)
{
  "code": 500,
  "msg": "库存不足, 商品=BOPP薄膜 20um×1000mm, 当前库存=5.0000, 需要=10.0000",
  "ts": 1700000000000
}

// 权限错误
{
  "code": 403,
  "msg": "无权限: system:user:add",
  "ts": 1700000000000
}

// 未登录
{
  "code": 401,
  "msg": "未登录或登录已过期",
  "ts": 1700000000000
}
```

## 十三、调用示例

```bash
# 登录
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# 返回
{"code":200,"msg":"操作成功","data":{"token":"xxx","userId":1,...}}

# 后续请求带上 token
curl http://localhost:8080/api/base/product/page?pageNum=1 \
  -H "Authorization: xxx"

# 销售出库审核
curl -X POST http://localhost:8080/api/sales/delivery/123/check \
  -H "Authorization: xxx"
```
