-- ====================================================================
-- v1.0.10+ 端权限分离 + sys_role_menu 主键修复
-- ====================================================================
-- 合并自:
--   22_add_client_type.sql   — 新增 client_type / dashboard_perm / client_scope 列
--   23_fix_role_menu_pk.sql  — 主键改为 (role_id, menu_id, client_type)
--
-- 背景:
--   v1.0.10 引入端权限分离 (PC/APP/BOTH), sys_role_menu 加 client_type 列,
--   但主键仍是 (role_id, menu_id), 导致同一菜单按不同 client_type 插入时主键冲突.
--
-- 顺序依赖: 先加列 + 迁移数据, 再改主键. 本文件按此顺序执行, 幂等.
-- ====================================================================

-- ============================================================
-- 第一部分: 加列 (来自 22_add_client_type.sql)
-- ============================================================

-- 1. sys_role_menu 加 client_type 列
ALTER TABLE `sys_role_menu`
  ADD COLUMN `client_type` VARCHAR(8) NOT NULL DEFAULT 'PC' COMMENT '客户端类型: PC/APP/BOTH' AFTER `menu_id`;

-- 2. 给已有数据标记为 BOTH (兼容升级前的角色权限)
UPDATE `sys_role_menu` SET `client_type` = 'BOTH' WHERE `client_type` = 'PC';

-- 3. sys_menu 加 dashboard_perm 标识
ALTER TABLE `sys_menu`
  ADD COLUMN `dashboard_perm` TINYINT NOT NULL DEFAULT 0 COMMENT '工作台 KPI 需独立鉴权 (0=否 1=是)' AFTER `is_visible`;

-- 4. sys_role 加 client_scope
ALTER TABLE `sys_role`
  ADD COLUMN `client_scope` VARCHAR(16) NOT NULL DEFAULT 'BOTH' COMMENT '允许登录的端: BOTH/PC/APP' AFTER `data_scope`;

-- ============================================================
-- 第二部分: 主键修复 (来自 23_fix_role_menu_pk.sql)
-- 依赖第一部分已执行, 确保 client_type 列存在
-- ============================================================

-- 1. 去重 (安全网: 按 (role_id, menu_id, client_type) 保留最早一行)
DELETE FROM sys_role_menu
WHERE (role_id, menu_id, client_type) IN (
  SELECT role_id, menu_id, client_type FROM (
    SELECT role_id, menu_id, client_type,
           ROW_NUMBER() OVER (PARTITION BY role_id, menu_id, client_type ORDER BY role_id) AS rn
    FROM sys_role_menu
  ) t WHERE rn > 1
);

-- 2. 删旧主键, 加新主键 (允许同一菜单以不同 client_type 多次分配)
ALTER TABLE sys_role_menu DROP PRIMARY KEY;
ALTER TABLE sys_role_menu ADD PRIMARY KEY (role_id, menu_id, client_type);

-- 3. 加索引 (role_id, client_type) 加快按端查询
CREATE INDEX idx_role_client ON sys_role_menu (role_id, client_type);
