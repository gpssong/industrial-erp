-- ====================================================================
-- v1.0.10+ 端权限分离: PC端菜单 vs App端菜单
-- ====================================================================
-- 背景: 之前角色分配菜单同时作用于 PC 和 App, 导致:
--   1. 角色拥有"采购订单"PC 权限, App 也显示"采购订单"tab (业务不需要)
--   2. 角色拥有"工作台"权限, App 登录后看到 KPI (今日销售等), 不应开放
-- 设计:
--   - sys_role_menu 加 client_type (PC/APP/BOTH), 默认 PC
--   - sys_role 加 client_scope (允许登录的端: BOTH/PC/APP)
--   - 工作台 KPI 接口 (/report/dashboard) 单独加 dashboard:view 权限
-- ====================================================================

-- 1. sys_role_menu 加 client_type 列 (不修改主键, 允许重复行)
-- (role_id, menu_id, client_type) 复合去重在 service 层保证
ALTER TABLE `sys_role_menu`
  ADD COLUMN `client_type` VARCHAR(8) NOT NULL DEFAULT 'PC' COMMENT '客户端类型: PC/APP/BOTH' AFTER `menu_id`;

-- 2. 给已有数据标记为 BOTH (兼容升级前的角色权限)
UPDATE `sys_role_menu` SET `client_type` = 'BOTH' WHERE `client_type` = 'PC';

-- 3. sys_menu 加 dashboard_perm 标识
-- (前端 dashboard.vue 按此字段决定是否调用 KPI API; 后端独立 requirePerm("dashboard:view"))
ALTER TABLE `sys_menu`
  ADD COLUMN `dashboard_perm` TINYINT NOT NULL DEFAULT 0 COMMENT '工作台 KPI 需独立鉴权 (0=否 1=是)' AFTER `is_visible`;

-- 4. sys_role 加 client_scope
ALTER TABLE `sys_role`
  ADD COLUMN `client_scope` VARCHAR(16) NOT NULL DEFAULT 'BOTH' COMMENT '允许登录的端: BOTH/PC/APP' AFTER `data_scope`;

-- 5. 新增"工作台数据查看"权限点
-- 不创建 sys_menu 行, 改在 SysRoleMenu 里单独维护 dashboard:view 的 perms
-- 实际做法: 后端 requirePerm("dashboard:view"), 不需要菜单项
-- 但 PermissionService.hasPerm 的"拥有 :list 即可"逻辑可能误开, 所以
-- 我们改用 requirePerm 不走 :list 覆盖: 显式 hasPermission("dashboard:view")
