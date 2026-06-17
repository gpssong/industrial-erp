# 数据库设计说明

## 一、ER 图核心实体关系

```
┌──────────┐       ┌──────────┐       ┌──────────┐
│ sys_user │──N:M──│ sys_role │──N:M──│ sys_menu │
└──────────┘       └──────────┘       └──────────┘
                       │
                       │ data_scope
                       ▼
                  ┌──────────┐
                  │ sys_dept │
                  └──────────┘

基础资料层
   base_supplier ──┐
                   │   购销关系
   base_customer ──┴──────────────┐
                                  │
base_product ──N:M── base_unit (多单位/换算)   │
       │                                       │
       │  BOM                                  │
       ▼                                       ▼
   prd_bom (主料/辅料/损耗)                pur_order (PO)
   prd_order (生产加工单)                  sal_order (SO)
   prd_requisition (领/补/退)             pur_receipt (入库)
   prd_process (工序)                     sal_delivery (出库)
   prd_finished_in (成品入库)             pur_return/sal_return

库存核心
                       ┌────────────────┐
                       │   inv_stock    │  ←  按 仓库+商品+批次 汇总
                       │  qty / avg_cost│     乐观锁 + 行锁
                       └──────┬─────────┘
                              │
                              ▼
                       ┌────────────────┐
                       │   inv_ledger   │  ←  全部出入库流水
                       └────────────────┘

财务往来
   fin_arap (应收/应付台账)  ←  pur_receipt / sal_delivery
   fin_cash_flow (收/付款)   →  核销 → fin_arap
   fin_reconciliation (对账单)
```

## 二、表清单 (60+ 张)

| 序号 | 模块 | 表 | 说明 |
|---|---|---|---|
| 1 | 系统 | `sys_dept / sys_role / sys_menu / sys_user / sys_user_role / sys_role_menu / sys_role_dept` | 权限基础 |
| 2 | 系统 | `sys_oper_log / sys_login_log` | 日志 |
| 3 | 系统 | `sys_config / sys_print_template / sys_backup_record` | 配置 |
| 4 | 系统 | `sys_dict_type / sys_dict_data / sys_notice / sys_bill_attachment` | 字典/通知/附件 |
| 5 | 基础 | `base_product_category / base_product / base_product_unit / base_unit` | 商品(工业版) |
| 6 | 基础 | `base_customer / base_supplier / base_warehouse / base_warehouse_area / base_warehouse_location / base_price_level` | 业务基础 |
| 7 | 采购 | `pur_order / pur_order_detail / pur_receipt / pur_receipt_detail / pur_return / pur_return_detail / pur_inquiry / pur_inquiry_detail / pur_payment` | 采购全流程 |
| 8 | 销售 | `sal_order / sal_order_detail / sal_delivery / sal_delivery_detail / sal_return / sal_return_detail / sal_quotation / sal_quotation_detail / sal_receipt` | 销售全流程 |
| 9 | 库存 | `inv_stock / inv_ledger / inv_transfer / inv_transfer_detail / inv_check / inv_check_detail / inv_profit_loss / inv_profit_loss_detail / inv_warning / inv_cut_process / inv_cut_process_detail` | 库存核心(含分切复卷) |
| 10 | 生产 | `prd_bom / prd_bom_detail / prd_order / prd_requisition / prd_requisition_detail / prd_process / prd_finished_in` | 生产管理 |
| 11 | 委外 | `out_issue / out_issue_detail / out_processing_in / out_processing_in_detail / out_process_fee` | 委外加工 |
| 12 | 财务 | `fin_arap / fin_cash_flow / fin_cash_writeoff / fin_reconciliation / fin_partner_balance` | 财务往来 |
| 13 | 杂项 | `inv_serial_no / rpt_daily_snapshot / sys_job_log` | 辅助 |

## 三、核心设计要点

### 3.1 并发控制
- `inv_stock` 主键 + 唯一键 `(warehouse_id, location_id, product_id, batch_no, deleted)` 形成天然行锁候选
- 启用 `version` 字段(乐观锁) + `SELECT ... FOR UPDATE` 悲观锁
- 出库时执行: `if (stock.qty < qty) throw BizException("库存不足")`, 拒绝负库存

### 3.2 软删除
- 所有业务表含 `deleted TINYINT DEFAULT 0`, 唯一索引都包含 `deleted` 列
- MyBatis-Plus 全局配置 `logic-delete-field: deleted`

### 3.3 审计字段
- 统一: `create_by / create_time / update_by / update_time / tenant_id`

### 3.4 金额/数量
- `DECIMAL(18,4)`, 数量保留4位, 金额保留4位
- 税率 `DECIMAL(8,2)`, 折扣率 `DECIMAL(8,4)`

### 3.5 单据号规则
- `PO+yyyyMMdd+0001` 采购订单
- `RKP+yyyyMMdd+0001` 采购入库
- `SO+yyyyMMdd+0001` 销售订单
- `CKP+yyyyMMdd+0001` 销售出库
- `PD+yyyyMMdd+0001` 生产加工单
- `OUT+yyyyMMdd+0001` 委外
- `INV+yyyyMMdd+0001` 盘点
- 序号由 Redis 自增保证全局唯一

### 3.6 多租户
- 预留 `tenant_id`, 后续可通过 MyBatis-Plus 多租户插件扩展

## 四、初始化数据

执行顺序:
```bash
mysql -uroot -p < 01_schema_system.sql
mysql -uroot -p < 02_schema_base.sql
mysql -uroot -p < 03_schema_purchase.sql
mysql -uroot -p < 04_schema_sales.sql
mysql -uroot -p < 05_schema_inventory.sql
mysql -uroot -p < 06_schema_production.sql
mysql -uroot -p < 07_schema_outsource_finance.sql
mysql -uroot -p < 08_schema_misc.sql
mysql -uroot -p < 09_seed_data.sql
```

或一键:
```bash
cat 0*.sql | mysql -uroot -p industrial_erp
```

默认账号: `admin / admin123`
