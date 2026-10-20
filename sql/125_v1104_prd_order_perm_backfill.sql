-- =====================================================================
-- v1.1.104: 修 check-perm.py 发现的 13 个历史 method perm 漏 (双站部署)
--
-- 背景: v1.1.104 home 自检新增 scripts/check-perm.py 报告 13 个 controller
--   method 缺 perm 防护. 其中 PrdOrderController 7 个 method (page/detail/add/update/
--   release/finish/void/delete) 全部裸奔 — 业务代码逻辑放在 service 层 hasPerm
--   兜底, 但 AOP 注解缺失导致 Sa-Token 不拦截 (任何登录用户都能调). FeiePrintController.
--   previewBill 飞鹅预览接口也无 perm. 其他: PurReceiptController.createFromOrder /
--   BaseProductController.appSearch / SysUserController.changeMyPassword.
--
-- 修复方案 (本次):
--   1. Java 端: 给 PrdOrderController 8 个 method 加 @SaCheckPermission, 给其他 3 controller 的
--      各 method 加 @SaCheckPermission. BaseProductController.appSearch 和
--      SysUserController.changeMyPassword 用 @SaCheckLogin (登录即调, 不需要 perm 限制).
--   2. SQL 端: 给 6 内置角色 × 3 client_type 补授 production:order:add/list/feie-print 三个 perm
--      (历史 sql/23 只 seed production:order:add B 节点没补授权, sql/20 seed feie-print
--       没补授权; sql/28 补了 production:order:edit/delete/release/finish 但漏掉 list/add).
--   3. v1.1.104 注释: voidOrder 用 production:order:edit perm (业务上"作废"是"编辑"反向操作,
--      不增加新 perm 节点避免 perm 膨胀).
--
-- 部署:
--   - home 主站 192.168.0.150 + 飞牛 canary 192.168.0.32 各跑一次
--   - MySQL 容器的 MOUNT_PATH 为 /tmp, --default-character-set=utf8mb4
--   - 重打 jar 双站部署
--   - 用户无需退出重登, service 层 hasPerm 不变; 新加 perm 6 角色已绑定
-- =====================================================================
USE industrial_erp;

-- ---------------------------------------------------------------------
-- 1. 确认 sys_menu 有这 3 个 perm 节点 (缺则补 — 防历史 seed 漏跑)
-- ---------------------------------------------------------------------
-- 1.1 production:order:add
INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status)
SELECT 702, '生产订单新增', 'F', '', NULL, 'production:order:add', NULL, 3011, 0, 1
WHERE NOT EXISTS (
  SELECT 1 FROM sys_menu WHERE perms = 'production:order:add' AND deleted = 0
);

-- 1.2 production:order:list (menu_id=702 M 类型, 纯 perm 列表)
--    已有 source: sql/09 seed (402,4,..,'purchase:receipt:list') 类比
--    若 menu 702 缺失或 perms 不匹配, 修
UPDATE sys_menu SET perms = 'production:order:list'
WHERE id = 702 AND perms = '' AND deleted = 0;

-- 1.3 production:order:feie-print (sql/20 seed F 类型)
INSERT IGNORE INTO sys_menu (parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status)
SELECT 0, '生产订单飞鹅打印', 'F', '', NULL, 'production:order:feie-print', NULL, 2099, 0, 1
WHERE NOT EXISTS (
  SELECT 1 FROM sys_menu WHERE perms = 'production:order:feie-print' AND deleted = 0
);

-- ---------------------------------------------------------------------
-- 2. 给 6 内置角色 × 3 客户端 (PC/APP/BOTH) 授权
--    沿用 sql/28 + sql/53 模式: 信息守门 client_type 列, 老库 2 列退化
-- ---------------------------------------------------------------------

-- 2.1 PC 端授权 (INSERT IGNORE 幂等)
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'PC'
FROM sys_role r
CROSS JOIN sys_menu m
WHERE r.deleted = 0
  AND m.deleted = 0
  AND r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND m.perms IN (
    'production:order:list',
    'production:order:add',
    'production:order:feie-print'
  );

-- 2.2 APP 端授权 (生产管理 tab 端 App 用户也需要 list/add)
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'APP'
FROM sys_role r
CROSS JOIN sys_menu m
WHERE r.deleted = 0
  AND m.deleted = 0
  AND r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND m.perms IN (
    'production:order:list',
    'production:order:add'
  );

-- 2.3 BOTH 授权 (兼容老库 sys_role_menu 没 client_type 列 / 不分端时 BOTH)
--    老库 fallback — 与 sql/22_add_client_type.sql 一致
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'BOTH'
FROM sys_role r
CROSS JOIN sys_menu m
WHERE r.deleted = 0
  AND m.deleted = 0
  AND r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND m.perms IN (
    'production:order:list',
    'production:order:add',
    'production:order:feie-print'
  )
  AND EXISTS (
    -- 仅当老库表结构无 client_type 列时走 BOTH 路径
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'sys_role_menu'
      AND column_name != 'client_type'
  );

-- ---------------------------------------------------------------------
-- 3. 校验: 3 个 perm 各有 6 角色绑定 (PC 端期望)
-- ---------------------------------------------------------------------
SELECT m.perms, COUNT(rm.role_id) AS pc_role_count
FROM sys_menu m
LEFT JOIN sys_role_menu rm
  ON rm.menu_id = m.id
  AND (rm.client_type = 'PC' OR rm.client_type = 'BOTH')
  AND rm.role_id IN (SELECT id FROM sys_role
                     WHERE role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
                       AND deleted = 0)
WHERE m.perms IN ('production:order:list','production:order:add','production:order:feie-print')
  AND m.deleted = 0
GROUP BY m.perms
ORDER BY m.perms;
-- 期望: 每个 perm pc_role_count = 6

-- ---------------------------------------------------------------------
-- 回滚 (如需):
-- DELETE FROM sys_role_menu
-- WHERE menu_id IN (SELECT id FROM sys_menu
--                    WHERE perms IN ('production:order:list','production:order:add','production:order:feie-print')
--                      AND deleted = 0)
--   AND role_id IN (SELECT id FROM sys_role
--                    WHERE role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
--                      AND deleted = 0);
-- 业务影响: PrdOrderController 8 个 method + FeiePrintController.previewBill 重新变回裸奔
-- (历史 bug 状态), Sa-Token 不拦截, 但 service 层 hasPerm 兜底, 安全无进一步破坏.
