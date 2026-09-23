-- =====================================================================
-- 三十五、v1.1.55 — 反审核独立 perm (`xxx:uncheck`)
-- =====================================================================
--
-- 背景:
--   2026-09-21 用户反馈: App 端无反审核权限的账号不显示反审核按钮.
--   v1.1.54 上线时, App 端审核/反审核共用 `xxx:check` 一个 perm
--   (项目惯例 + 后端 PurReceiptController.java:74 / SalDeliveryController.java:86
--   用 `@SaCheckPermission("xxx:check")`), 业务上不能精细控制"能审核" 与
--   "敢反审核" (反审核回退库存/AP/AR, 高风险, 通常只给老板/主管/超管).
--
-- 根因 (Phase 1 调研):
--   - **后端 Service 层早就用 `:uncheck`**: PurReceiptService.java:322 +
--     SalDeliveryService.java:386 都 `permService.requirePerm("xxx:uncheck")`,
--     但 controller 层 @SaCheckPermission 走 `:check` 用 Spring AOP 先拦截,
--     service 层 requirePerm 永远到不了 — 项目早已为"拆 perm"埋好设计, 只是
--     controller 注解 + sys_menu seed 没对齐.
--   - **PC 端前端早就用 `:uncheck` perm**: Receipt.vue:45 + Delivery.vue:60
--     早已 `userStore.hasPerm('purchase:receipt:uncheck')`, 但 sys_menu 从未
--     seed 过该 perm → **PC 端反审核按钮 4 年来对所有非超管账号永久隐藏**
--     (这是个隐性 BUG, 拆 perm 一并修复).
--   - **PermissionService.hasPerm 有 fallback**: `:add`/`:edit`/`:delete`
--     自动 fallback 到 `:list`, 但 `:uncheck` **不** fallback 到 `:check`,
--     必须真有 perm 才放行 → 新 perm 必须 seed + 绑角色.
--
-- 修复 (3 件事):
--   1) sys_menu 新增 `purchase:receipt:uncheck` + `sales:delivery:uncheck`
--      2 行 perm 载体 (F 类型, parent_id=0, is_visible=0, sort_no=2015/1015)
--   2) 凡是有 check APP 授权的角色 → 自动补 uncheck APP 授权
--      (沿用 sql/34 模式, "有 check 才有 uncheck" 比"有 list 就有 uncheck"
--       更符合"反审核是更高级操作"的业务语义)
--   3) 给 6 个内置角色 (SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/WAREHOUSE_MGR/
--      PRODUCTION_MGR/FINANCE) 补 uncheck PC 授权 (CROSS JOIN, 与 sql/28
--      对称), 修复 PC 端 4 年来反审核按钮隐藏的 BUG
--
-- 设计原则 (R5):
--   - **何时拆**: 反审核 vs 审核 (风险/频率/可观察性不对称)
--   - **何时不拆**: add/edit (对称低风险); print/export (派生只读);
--     已有 perm 不批量重塑
--   - **后续模块** (红冲/补差等) 一律拆 `:uncheck` 独立 perm; 删除类暂不拆
--     (软删除+二次确认已够); 不批量改造历史 perm
--
-- ⚠️ MySQL 8 容器默认 latin1, 必须:
--   docker exec -i erp-mysql mysql industrial_erp -uroot -p$PW \
--     --default-character-set=utf8mb4 < 35_v155_uncheck_perm.sql
--
-- ⚠️⚠️⚠️ v1.1.55 hotfix 2026-09-21 晚 (sql/38 撤销 WAREHOUSE_MGR uncheck APP) ⚠️⚠️⚠️
--   sql/35 第 2-3 段 ("有 check APP 补 uncheck APP") 对所有 check APP 角色自动补 uncheck,
--   其中 WAREHOUSE_MGR (仓库主管) 业务上"可审核但不敢反审核", 但自动授权给补了.
--   用户反馈: App 端销售出库详情显示了【反审核】按钮, 业务上仓管员不应有.
--   修复: 跑 sql/38_v155_hotfix_revoke_warehouse_uncheck.sql 物理 DELETE
--         WAREHOUSE_MGR 的 purchase:receipt:uncheck APP + sales:delivery:uncheck APP 两行.
--   PC 端 uncheck 保留 (PC 业务流需要, 仓管员可通过 PC 反审核).
--
--   后续设计原则 (R7): sql/35 这种"自动补"策略仅适用于**6 个内置业务角色** (老板/超管/采购经理/销售经理/财务/生产主管),
--   WAREHOUSE_MGR 这类"可正向不能反向"角色应**手动**走 PC 端 Role.vue 勾选反审核 APP. 不再依赖 sql 触发器自动补.
-- =====================================================================

USE `industrial_erp`;

-- 1. 新增 :uncheck perm 行 (F 类型, parent_id=0, is_visible=0)
--    sort_no=2015/1015 与 check (2014/1014) 紧邻但不与 sql/28 现有 sort_no 冲突
INSERT IGNORE INTO `sys_menu`(`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
(0,'采购入库反审核','F','',NULL,'purchase:receipt:uncheck',NULL,2015,0,1),
(0,'销售出库反审核','F','',NULL,'sales:delivery:uncheck',NULL,1015,0,1);

-- 2. 采购入库反审核: 凡是有 purchase:receipt:check APP 授权的角色, 补 purchase:receipt:uncheck APP 授权
INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`,`client_type`)
SELECT DISTINCT rm.role_id, m.id, 'APP'
FROM `sys_role_menu` rm
JOIN `sys_menu` m ON m.perms = 'purchase:receipt:uncheck' AND m.deleted = 0
WHERE rm.menu_id IN (SELECT id FROM `sys_menu` WHERE perms = 'purchase:receipt:check' AND deleted = 0)
  AND rm.client_type = 'APP';

-- 3. 销售出库反审核: 同模式
INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`,`client_type`)
SELECT DISTINCT rm.role_id, m.id, 'APP'
FROM `sys_role_menu` rm
JOIN `sys_menu` m ON m.perms = 'sales:delivery:uncheck' AND m.deleted = 0
WHERE rm.menu_id IN (SELECT id FROM `sys_menu` WHERE perms = 'sales:delivery:check' AND deleted = 0)
  AND rm.client_type = 'APP';

-- 4. 给所有内置业务角色绑 uncheck PC (CROSS JOIN, 修复 PC 端 4 年 BUG)
--    与 sql/28:132-166 给 6 内置角色绑 check PC 对称
INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`)
SELECT r.id, m.id
FROM `sys_role` r
CROSS JOIN `sys_menu` m
WHERE r.deleted = 0
  AND r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND m.deleted = 0
  AND m.perms IN ('purchase:receipt:uncheck','sales:delivery:uncheck');

-- 5. 校验: 内置角色同时有 check + uncheck (PC 端 + APP 端)
SELECT r.role_code, r.role_name,
       -- PC 端
       EXISTS(SELECT 1 FROM `sys_role_menu` rm JOIN `sys_menu` m ON m.id = rm.menu_id
              WHERE rm.role_id = r.id AND m.perms = 'purchase:receipt:check'
                AND rm.client_type = 'PC') AS pur_check_pc,
       EXISTS(SELECT 1 FROM `sys_role_menu` rm JOIN `sys_menu` m ON m.id = rm.menu_id
              WHERE rm.role_id = r.id AND m.perms = 'purchase:receipt:uncheck'
                AND rm.client_type = 'PC') AS pur_uncheck_pc,
       EXISTS(SELECT 1 FROM `sys_role_menu` rm JOIN `sys_menu` m ON m.id = rm.menu_id
              WHERE rm.role_id = r.id AND m.perms = 'sales:delivery:check'
                AND rm.client_type = 'PC') AS sal_check_pc,
       EXISTS(SELECT 1 FROM `sys_role_menu` rm JOIN `sys_menu` m ON m.id = rm.menu_id
              WHERE rm.role_id = r.id AND m.perms = 'sales:delivery:uncheck'
                AND rm.client_type = 'PC') AS sal_uncheck_pc,
       -- APP 端
       EXISTS(SELECT 1 FROM `sys_role_menu` rm JOIN `sys_menu` m ON m.id = rm.menu_id
              WHERE rm.role_id = r.id AND m.perms = 'purchase:receipt:check'
                AND rm.client_type = 'APP') AS pur_check_app,
       EXISTS(SELECT 1 FROM `sys_role_menu` rm JOIN `sys_menu` m ON m.id = rm.menu_id
              WHERE rm.role_id = r.id AND m.perms = 'purchase:receipt:uncheck'
                AND rm.client_type = 'APP') AS pur_uncheck_app
FROM `sys_role` r
WHERE r.deleted = 0
ORDER BY r.id;

-- 预期:
--   - 6 个内置角色: pur_check_pc=1, pur_uncheck_pc=1, sal_check_pc=1, sal_uncheck_pc=1
--   - 制袋工(zdg) / 吹膜工(cmg) (v1.1.27+ 新建): pur_check_app=1, pur_uncheck_app=1,
--     PC 列=0 (从一开始 PC 端就没绑, sql/35 也不补, 行为一致)
--   - 没 list APP 的角色: check_app=0, uncheck_app=0 (sql/35 不会自动绑)

-- 6. 用户操作:
--    - 受影响的 App 用户**退出 App 重新登录**一次, Sa-Token session 缓存的
--      perm 数组从 DB 重新加载, 新授权立即生效
--    - PC 端内置非超管账号 (PURCHASE_MGR/SALES_MGR 等) 现在 PC 端入库单/出库单
--      列表会看到反审核按钮 (4 年 BUG 修复). 该变化属于预期行为, 但需在
--      通知/CHANGELOG 里说清楚, 否则用户可能惊讶"为什么反审核按钮突然出现了"