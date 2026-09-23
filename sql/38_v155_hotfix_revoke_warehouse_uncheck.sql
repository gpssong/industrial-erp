-- =====================================================================
-- 三十七、v1.1.55 hotfix (Part 2) — 撤销 WAREHOUSE_MGR 自动 uncheck APP
-- 2026-09-21 晚
-- =====================================================================
--
-- 背景:
--   v1.1.55 sql/35 触发器 ("有 check APP 补 uncheck APP") 给 WAREHOUSE_MGR
--   (仓库主管) 自动补了 purchase:receipt:uncheck APP + sales:delivery:uncheck APP.
--   用户反馈: 业务上仓管员不应有反审核权限 (反审核回退库存/AP/AR, 高风险,
--   通常只给老板/超管), 但 App 端销售出库详情显示了【反审核】按钮.
--
--   根因: sql/35 自动授权策略 (R5 设计原则) 把 check 与 uncheck 等价,
--   业务上"能审核"与"敢反审核"应该分离. WAREHOUSE_MGR 这种"可正向不能反向"
--   的角色应排除在外.
--
-- 修复 (用户决策):
--   撤销 WAREHOUSE_MGR 的 uncheck APP (App 端立即不可见反审核按钮).
--   仓库主管: 保留 check APP (审核) + 移除 uncheck APP (反审核).
--   老板/超管 (SUPER_ADMIN) / 财务 (FINANCE) / 采购经理 (PURCHASE_MGR) /
--   销售经理 (SALES_MGR) / 生产主管 (PRODUCTION_MGR) 保持现状不动.
--
-- 注意:
--   - 这是"撤销 sql/35 触发器效果", 不需要重跑 sql/35 也不会被它影响
--     (INSERT IGNORE 不会重新插已存在的行, 我们这里是 DELETE 物理删除)
--   - PC 端 uncheck 保留 (sql/35 第 4 段给 6 内置角色 CROSS JOIN 的),
--     因为 PC 端反审核按钮对仓库主管业务上有意义 (可走 PC 走流程, 不走 App)
--   - 后续新增"可审核不能反审核"的 App 角色, 在 PC 端 Role.vue 手动
--     取消勾选 uncheck APP 即可, 无需再跑 SQL
-- =====================================================================

USE `industrial_erp`;

-- 1. 撤销 WAREHOUSE_MGR 的 uncheck APP (物理 DELETE, 不留痕迹)
DELETE rm FROM `sys_role_menu` rm
JOIN `sys_role` r ON r.id=rm.role_id AND r.deleted=0
JOIN `sys_menu` m ON m.id=rm.menu_id AND m.deleted=0
WHERE r.role_code='WAREHOUSE_MGR'
  AND rm.client_type='APP'
  AND m.perms IN ('purchase:receipt:uncheck','sales:delivery:uncheck');

-- 2. 校验: WAREHOUSE_MGR 现在 APP 端 uncheck=0, PC 端 uncheck=1 (保留)
SELECT r.role_code, r.role_name,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='purchase:receipt:check'   AND rm.client_type='APP') AS pur_check_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='purchase:receipt:uncheck' AND rm.client_type='APP') AS pur_uncheck_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='sales:delivery:check'   AND rm.client_type='APP') AS sal_check_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='sales:delivery:uncheck' AND rm.client_type='APP') AS sal_uncheck_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='purchase:receipt:check'   AND rm.client_type='PC')  AS pur_check_pc,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='purchase:receipt:uncheck' AND rm.client_type='PC')  AS pur_uncheck_pc,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='sales:delivery:check'   AND rm.client_type='PC')  AS sal_check_pc,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='sales:delivery:uncheck' AND rm.client_type='PC')  AS sal_uncheck_pc
FROM sys_role r WHERE r.deleted=0 AND r.role_code='WAREHOUSE_MGR';

-- 预期:
--   pur_check_app=1, pur_uncheck_app=0
--   sal_check_app=1, sal_uncheck_app=0
--   pur_check_pc=1,  pur_uncheck_pc=1
--   sal_check_pc=1,  sal_uncheck_pc=1

-- 3. 用户操作: gpssong1 退出 App 重登一次 → 反审核按钮消失
--    PC 端不变 (uncheck 仍绑, 业务上仓库主管可通过 PC 反审核)
