-- =====================================================================
-- v1.1.137: 生产工单「飞鹅云打印」权限点启用 + 按端授权
-- ---------------------------------------------------------------------
-- 背景: PC 生产工单详情页新增打印下拉 (浏览器打印 / 飞鹅打印预览 / 飞鹅云打印),
--       App 生产单详情页的「飞鹅云打印」按钮自 v1.1.9 起一直存在但从没真正可用。
--
-- 根因 (sys_menu id=959, perms=production:order:feie-print, parent=702 生产加工单):
--   1) sys_menu.status = 0 → 前端登录时 selectPermsByUserIdAndClient 要求 m.status = 1,
--      这个 perm 永远发不到任何非超管用户 → PC 下拉里的飞鹅两项、App 的按钮全部不显示;
--   2) sys_role_menu 只有 role_id=1 / client_type='PC' 一行 → 后端 @SaCheckPermission
--      走 selectPermsByUserId (**不筛 status、不筛端**), 超管靠这一行能通过,
--      生产主管/制袋工/吹膜工 一律 403;
--   3) orRole = "admin" 对本项目无效 — sys_role.role_code 是 'SUPER_ADMIN',
--      Sa-Token 的 orRole 是精确匹配, 不匹配 'SUPER_ADMIN'。超管能用全靠第 2 条那一行真实授权。
--
-- 修法: status 0→1 (启用权限点) + 给 5 个生产相关角色按其已有的 702 授权端镜像补 959 授权行。
--       采购经理/销售经理 虽也有 702 但不在名单内 (生产小票与她们无关)。
--
-- ⚠️ R24 守门: UPDATE 带 status <> 1 条件, INSERT 带 NOT EXISTS, 连跑 N 次幂等。
-- 部署: 三站都灌 (飞牛 canary 先验证 → .150 主库 → 阿里云)。灌完后用户需**重新登录**
--       (perm 在登录时写入前端 localStorage 与后端 Session)。
--   docker exec -i erp-mysql[-failover] mysql industrial_erp -uroot -p<pwd> \
--          --default-character-set=utf8mb4 < 145_v1137_feie_print_perm_enable.sql
-- =====================================================================

USE industrial_erp;

-- 1. 启用「飞鹅打印」权限点 (幂等)
UPDATE sys_menu
SET status = 1
WHERE perms = 'production:order:feie-print'
  AND deleted = 0
  AND status <> 1;

-- 2. 按端镜像授权: 已有「生产加工单」(menu 702) 授权的生产相关角色, 同端补 959
INSERT INTO sys_role_menu (role_id, menu_id, client_type)
SELECT rm.role_id, m.id, rm.client_type
FROM sys_role_menu rm
JOIN sys_role r ON r.id = rm.role_id AND r.deleted = 0 AND r.status = 1
CROSS JOIN sys_menu m
WHERE rm.menu_id = 702
  AND m.perms = 'production:order:feie-print'
  AND m.deleted = 0
  AND r.role_code IN ('SUPER_ADMIN', 'PRODUCTION_MGR', 'WAREHOUSE_MGR', 'zdg', 'cmg')
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu x
    WHERE x.role_id = rm.role_id AND x.menu_id = m.id AND x.client_type = rm.client_type
  );

-- 3. 验证 — 期望: 权限点 status=1; 授权行覆盖 SUPER_ADMIN(PC/APP/BOTH) +
--    PRODUCTION_MGR(PC/APP) + WAREHOUSE_MGR(PC/APP) + zdg(PC/APP) + cmg(PC)
SELECT id, parent_id, menu_name, perms, status, is_visible, client_type
FROM sys_menu
WHERE perms = 'production:order:feie-print';

SELECT r.role_code, rm.client_type, m.perms
FROM sys_role r
JOIN sys_role_menu rm ON rm.role_id = r.id
JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.perms = 'production:order:feie-print'
  AND r.deleted = 0
ORDER BY r.role_code, rm.client_type;
