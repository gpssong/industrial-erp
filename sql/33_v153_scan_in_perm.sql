-- =====================================================================
-- 三十三、v1.1.53.1 — App 端扫码入库 add perm APP 授权补齐 (hotfix)
-- =====================================================================
--
-- 背景:
--   2026-09-21 秦运桂(角色=制袋工 zdg, role_id=2079395004410298370) 反馈:
--   App 端「扫码入库」打开 OK, 但点「确认入库」提示「提交失败: 无权限访问」.
--
-- 根因:
--   - App 端菜单「扫码入库」走 menu_id=402 (perms=`purchase:receipt:list`)
--   - 但提交调 `POST /purchase/receipt`, 后端 `@SaCheckPermission("purchase:receipt:add")`
--   - perm `purchase:receipt:add` (menu_id=2090345792472715280, F 类型按钮权限) 在
--     `sys_role_menu` 里**只配了 PC 端**, 没有 APP 端
--   - sql/28_v124_permissions.sql seed 时只给 6 个内置角色 (SUPER_ADMIN/PURCHASE_MGR/
--     SALES_MGR/WAREHOUSE_MGR/PRODUCTION_MGR/FINANCE) 授 add, **未覆盖 v1.1.27 后新建的
--     非标角色** (制袋工 zdg / 吹膜工 cmg 等)
--
-- 修复:
--   凡是有 `purchase:receipt:list` APP 授权的角色, 自动补一份 `purchase:receipt:add` APP 授权.
--   (同 mode 也可应用到 `sales:delivery:add` / `sales:order:add` / `production:order:add` /
--    `inventory:check:add` 等其他"页面菜单 + 按钮权限"组合, 后续按需扩展)
--
-- 设计考虑:
--   - 用 INSERT IGNORE 兼容历史 hotfix 数据 (idempotent)
--   - WHERE 条件限定 client_type='APP' 触发, 不影响 PC 端已有授权
--   - 不删任何已有 PC/APP 记录 (只新增缺失的 APP add 行)
--
-- ⚠️ MySQL 8 容器默认 latin1, 必须:
--   docker exec -i erp-mysql mysql industrial_erp -uroot -p$PW \
--     --default-character-set=utf8mb4 < 33_v153_scan_in_perm.sql
-- 否则含中文的菜单名会 mojibake (此脚本无中文 INSERT, 但养成习惯)
-- =====================================================================

USE `industrial_erp`;

-- 1. 给所有"已存在 purchase:receipt:list APP 授权"的角色, 追加 purchase:receipt:add APP 授权
INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`,`client_type`)
SELECT DISTINCT rm.role_id, 2090345792472715280, 'APP'
FROM sys_role_menu rm
WHERE rm.menu_id = 402           -- purchase:receipt:list 的 menu_id
  AND rm.client_type = 'APP';     -- 只看 APP 端授权, 避免给纯 PC 角色加 APP

-- 2. 校验: 凡是有 list APP 授权的角色, 现在都应该同时有 add APP 授权
SELECT r.role_code, r.role_name,
       EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=r.id AND menu_id=402 AND client_type='APP') AS has_list,
       EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=r.id AND menu_id=2090345792472715280 AND client_type='APP') AS has_add
FROM sys_role r
WHERE r.deleted = 0
ORDER BY r.id;

-- 预期: 6 个内置角色 + 制袋工(zdg) + 吹膜工(cmg) 都 has_list=1, has_add=1
-- (老吹膜工如果没人授 list APP 授权, has_list=0, has_add=0, 这是预期的)

-- 3. 用户操作: 让秦运桂等 App 用户**退出 App 重新登录**一次
--    Sa-Token session 缓存的权限会从 DB 重新加载, 新授权立即生效
