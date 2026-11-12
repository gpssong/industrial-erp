-- =====================================================================
-- v1.1.154: App「现场填报 → 设备台账」支持新增设备 — APP 端授权补项
-- ---------------------------------------------------------------------
-- 背景: App 设备台账页 (pages/equipment/my.vue) 新增「＋ 新增设备」入口,
--       走 POST /base/equipment (@SaCheckPermission equipment:master:add)。
--       App 端按钮显隐走 getAppPermissions() — 从 /me 的 appMenus (sys_role_menu
--       client_type='APP') 派生, 而设备台账此前只授过 equipment:master:list,
--       所以除超管外 App 里看不到新增入口。
--
-- 授权口径: 「PC 端能新增设备」且「APP 端能看设备台账」的角色 → 补一行 APP 新增授权。
--           (与 PC 端已有授权保持一致, 不额外扩大范围)
--
-- 幂等: sys_role_menu 主键 (role_id, menu_id, client_type) + NOT EXISTS 双保险。
-- 部署: 灌 .150 主库, 由复制带到 .32 / 阿里云; 用户需重新登录 (App 端 erp_menus 在登录时写入)。
--   printf 'pw\n' | sudo -S -p '' docker exec -i erp-mysql mysql industrial_erp -uroot -p"$ERP_MYSQL_ROOT_PWD" \
--          --default-character-set=utf8mb4 < 148_v11154_app_equipment_add_perm.sql
-- =====================================================================

USE industrial_erp;

INSERT INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m_add.id, 'APP'
FROM sys_role r
JOIN sys_menu m_add ON m_add.perms = 'equipment:master:add' AND m_add.deleted = 0
WHERE r.deleted = 0
  AND r.status = 1
  AND EXISTS (
    SELECT 1 FROM sys_role_menu rm_pc
    JOIN sys_menu m_pc ON m_pc.id = rm_pc.menu_id
    WHERE rm_pc.role_id = r.id AND rm_pc.client_type = 'PC'
      AND m_pc.perms = 'equipment:master:add' AND m_pc.deleted = 0
  )
  AND EXISTS (
    SELECT 1 FROM sys_role_menu rm_app
    JOIN sys_menu m_app ON m_app.id = rm_app.menu_id
    WHERE rm_app.role_id = r.id AND rm_app.client_type = 'APP'
      AND m_app.perms = 'equipment:master:list' AND m_app.deleted = 0
  )
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu x
    WHERE x.role_id = r.id AND x.menu_id = m_add.id AND x.client_type = 'APP'
  );

-- 验证 — 期望: WAREHOUSE_MGR / PRODUCTION_MGR / FINANCE 等「PC 能新增 + APP 能看」的角色
--       出现一行 client_type='APP' 的 equipment:master:add
SELECT r.role_code AS role, rm.client_type AS ct, m.perms AS perm
FROM sys_role r
JOIN sys_role_menu rm ON rm.role_id = r.id
JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.perms = 'equipment:master:add'
  AND r.deleted = 0
ORDER BY r.role_code, rm.client_type;
