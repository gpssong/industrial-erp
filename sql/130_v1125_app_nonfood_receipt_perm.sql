-- =====================================================================
-- v1.1.125: App H5「非食品采购·免检直通」建单页 — 补 APP 端 perm 授权
-- ---------------------------------------------------------------------
-- 背景 (v1.1.125 App H5 新增功能):
--   app/src/pages/purchase/receipt-add.vue 「非食品采购·免检直通」建单页,
--   从「采购入库单」列表页 receipt-list.vue 进入。该页要用到 3 类后端 perm:
--     1. POST /purchase/receipt              → purchase:receipt:add   (建草稿)
--     2. GET  /base/supplier/list            → base:supplier:list    (拉供应商下拉)
--     3. GET  /base/warehouse/list           → base:warehouse:list   (拉仓库下拉)
--
-- 实测 (飞牛 canary sys_role_menu): 这 3 个 perm **只有 PC 端授权**, APP 端 0 行。
--   后端 selectPermsByUserId() 在 **非超管** 走 StpUtil.hasPermission 时合并 PC+APP 全部
--   (sys_menu.id 级 JOIN, 不按端过滤) → PC 端已授这三个 perm 的普通角色在 App 端**能调通**这 3 个端点;
--   但 @SaCheckPermission(orRole="admin") 对非超管是 **精确字面匹配** — 没有 APP 授权行的用户
--   即便 PC 有这三个 perm 也会 403。且「采购入库单」列表页 / 工作台 dashboard 的 APP 端菜单
--   派生走 selectPermsByUserIdAndClient(按端严格过滤), 缺 APP 授权 → 列表页在 App 端可能不显示。
--
-- 根因归类 (与 v1.1.103 sql/124 同型): 建表 seed 只授了 PC, APP 端漏授权。
-- 修法: 给 6 内置业务角色 × 3 perm 补 APP 端授权 (NOT EXISTS 守门, 幂等, 连跑 3 次安全)。
--       base:product:list 已有 APP 授权 (App 端商品搜索走 /app-search 只需登录), 不在本脚本。
--
-- ⚠️ R24: 本脚本纯 INSERT + NOT EXISTS 守门, **无状态位 UPDATE**, 连跑 N 次都是跳过, 安全。
-- 部署: 双站都灌 (飞牛 canary 先, home 后)。
--   docker exec -i erp-mysql[-failover] mysql industrial_erp -uroot -p<pwd> \
--          --default-character-set=utf8mb4 < 130_v1125_app_nonfood_receipt_perm.sql
-- 验证: 灌完后**重新登录** App (perm 在登录时缓存到 Session), 列表页应显示「非食品采购·免检直通」按钮。
-- =====================================================================

USE industrial_erp;

-- 1. 给 6 内置业务角色 × 3 perm 补 APP 端授权 (NOT EXISTS 守门)
INSERT INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'APP'
FROM sys_role r
CROSS JOIN sys_menu m
WHERE m.perms IN (
    'purchase:receipt:add',
    'base:supplier:list',
    'base:warehouse:list'
  )
  AND m.deleted = 0
  AND r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND r.deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm
    WHERE rm.role_id = r.id AND rm.menu_id = m.id AND rm.client_type = 'APP'
  );

-- 2. 验证 — 期望: 6 角色 × 3 perm × APP = 18 行 (其中 base:* 若原本已有 APP 授权的会被 NOT EXISTS 跳过)
SELECT r.role_code, m.perms, rm.client_type
FROM sys_role r
JOIN sys_role_menu rm ON rm.role_id = r.id
JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.perms IN ('purchase:receipt:add','base:supplier:list','base:warehouse:list')
  AND m.deleted = 0 AND r.deleted = 0 AND rm.client_type = 'APP'
ORDER BY r.role_code, m.perms;
