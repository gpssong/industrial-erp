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

---

## 数据漂移巡检 (`scripts/sql-drift-check.sh`, v1.1.104 起)

双站 (home `192.168.0.150` + 飞牛 `192.168.0.32`) 各跑一份 `industrial_erp`, 列结构靠手动
`docker cp` SQL + `SOURCE` 同步。漏灌一列就表现为 "home 某功能 500 / 飞牛正常", 排查靠肉眼。
本脚本固化这件事: 从 `sql/` 源码推导出 **期望表+列清单**, 拉 live DB 的 `INFORMATION_SCHEMA`
对比, 自动输出 "live 缺哪些表/列" 与 "live 多哪些表/列"。

```bash
# 默认连 home (192.168.0.150), 只报"缺"
./scripts/sql-drift-check.sh

# 连飞牛 canary
./scripts/sql-drift-check.sh --host 192.168.0.32

# 同时报"live 多出的表/列" (评估是否回收临时表 / 回写 sql/)
./scripts/sql-drift-check.sh --host 192.168.0.150 --extra

# 离线自检: 只推导期望清单, 不连库 (CI 上可用)
./scripts/sql-drift-check.sh --dry-run
```

覆盖密码/主机可走环境变量: `DB_HOST / DB_PORT / DB_USER / DB_PWD / DB_NAME`
(默认 `root` / `erp_root_pwd` / `industrial_erp`, 与 `docker-compose` 保持一致)。

**首次巡检结果 (2026-10-20)**: home 缺 8 列 / 飞牛缺 7 列, 集中在
`sql/17_add_model_field.sql` (明细表的 `model` 列) 与
`sql/24_add_invoice_tables.sql` (`fin_invoice_apply.invoice_status` /
`fin_invoice_apply.uninvoiced_amount`), home 另缺 `base_equipment.photo_url`
(`sql/107`)。

**补灌 (2026-10-20, 双站已绿)**: 因 sql/17、sql/24 是裸 `ALTER TABLE ADD COLUMN`
(非幂等, 整文件重跑会 Duplicate), 单独写 **`sql/90_drift_fill_columns.sql`** —
全部用 `information_schema` 探测 + `PREPARE/EXECUTE` 幂等写法, 缺哪列补哪列, 重跑 0 副作用。
飞牛 canary 先灌复检无漂移 → home 再灌复检无漂移。双站现均 `缺 0 表 / 0 列`。

```bash
# 补灌双站缺列 (本机有 mysql 客户端 + 能直连 3306 时):
mysql -h192.168.0.32 -uroot -perp_root_pwd industrial_erp --default-character-set=utf8mb4 < sql/90_drift_fill_columns.sql   # 飞牛
mysql -h192.168.0.150 -uroot -perp_root_pwd industrial_erp --default-character-set=utf8mb4 < sql/90_drift_fill_columns.sql  # home
# 灌后各站 sql-drift-check.sh 复检 → 应报 "无漂移"
```

---

## SQL 编号约定 (v1.1.124 起)

**编号 = 执行顺序, 必须全仓唯一。** 命名: `sql/<NNN>_<用途>.sql` 或 `sql/<NNN>_v<版本号>_<用途>.sql`。

### ⚠️ 为什么要唯一

编号重号会**让人跑错脚本** —— 尤其是含状态位 UPDATE 的幂等脚本:
- `sql/128_v1119_bom_ratio_semantics.sql` 把 3 个膜料主单位切到 kg, 含 `SET is_main = 0`
  这种**状态位 UPDATE**。按 R24, 这类脚本**必须连跑 3 次验证** —— 但若同编号还挂着另一个
  待执行脚本, 「跑 128」到底是哪个? 编号唯一是跑对脚本的前提。
- 实例: `sql/128_v1119_mes_weight_device.sql` (称重设备) 曾与 `sql/128_v1119_bom_ratio_semantics.sql`
  重号, **已于 v1.1.124 改名为 `sql/129_v1119_mes_weight_device.sql`**。

### 新增脚本前必做

```bash
# 1. 查编号是否被占
ls sql/<NNN>_*.sql
# 2. 改完后再全仓扫一遍重号
ls sql/*.sql | sed -E 's|.*/([0-9]+)_.*|\1|' | sort -n | uniq -d
#    (输出为空 = 无重号)
```

### 已知历史重号 (均已落地执行, **勿改名** —— 改名会破坏已记录的部署路径/文档引用)

| 编号 | 文件 A | 文件 B |
|---|---|---|
| 21 | `21_add_inv_check_menu.sql` | `21_add_sys_feie_print_log.sql` |
| 22 | `22_add_client_type_and_fix_pk.sql` | `22_add_sys_feie_print_template.sql` |
| 24 | `24_add_invoice_tables.sql` | `24_migrate_tax_inclusive.sql` |
| 30 | `30_add_sales_order_po_delivery.sql` | `30_v153_dashboard_subperms.sql` |
| 46 | `46_v168hotfix_cost_backfill.sql` | `46_v169_recycle_report_view_grant.sql` |
| 53 | `53_v180_feie_template_grant.sql` | `53_v180_mes_workorder.sql` |
| 117 | `117_v1101_qms_iqc_attachment.sql` | `117_v1103_home_fix_base_product_columns.sql` |
| 124 | `124_v1103_home_production_app_perm.sql` | `124_v1103_home_production_perm_full.sql` |
| 125 | `125_v1104_prd_order_perm_backfill.sql` | `125_v1114_sales_delivery_app_perm.sql` |

> 这 9 组**都已执行落地** (最晚 2026-10-22), 线上表/列/perm 均已生效。保留原编号是为了
> 不破坏 CHANGELOG / memory / 部署记录里的路径引用。**新增脚本一律不得再重号。**
