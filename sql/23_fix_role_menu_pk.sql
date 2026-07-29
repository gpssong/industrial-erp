-- ====================================================================
-- v1.0.10+ 修复 sys_role_menu 主键冲突
-- ====================================================================
-- 背景: v1.0.10 引入 client_type 字段后, 主键仍是 (role_id, menu_id).
--       当某 (role_id, menu_id) 已存在 client_type='BOTH' 记录 (来自迁移),
--       再插入 client_type='APP' 或 'PC' 时会因主键重复失败.
-- 修复: 主键改为 (role_id, menu_id, client_type), 允许同一菜单以不同 client_type 多次分配.
-- ====================================================================

-- 1. 先去重 (按 (role_id, menu_id, client_type) 保留最早一行)
-- 当前数据来自 22_add_client_type.sql 的迁移 (全标 BOTH), 实际无 client_type 维度重复,
-- 但保留此步骤作为安全网
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
