-- =====================================================================
-- v1.1.155 — App「检验设备校准」APP 端授权
--   需求: APP 端添加检验设备清单和检定提醒及记录和报告的上传和查看
--   后端零改动: 端点 + 权限码都在 v1.1.97 (sql/105) 建好, 本次只补 sys_role_menu 的 APP 行.
--   配套代码: PC Role.vue APP_MENU_WHITELIST + AppModules.vue; App pages/equipment/calibration*.vue
-- =====================================================================
-- 用法 (注意复制链!):
--   1. 飞牛 canary (.32) 先跑本脚本做功能演练;
--   2. **灌主库 (.150) 之前, 先把 canary 上这 5 个 perm 的 APP 授权行删掉**
--      (DB-4 步骤见文末), 否则主库 INSERT 复制到 canary 会撞 1062 → 复制停.
--   3. 主库跑完后 canary 会通过复制自动拿到同样的行.
--   字符集: 本脚本无中文写入 sys_menu, 仍建议 --default-character-set=utf8mb4.
-- =====================================================================

USE `industrial_erp`;

-- ------------------------------------------------------------------
-- 1. 头表是否有 client_type 列 (老库可能没有 → 退化为不分端授权)
-- ------------------------------------------------------------------
SET @has_ct := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role_menu' AND COLUMN_NAME = 'client_type'
);

-- ------------------------------------------------------------------
-- 2. 补 APP 授权行
--    范围: 只给「PC 端已有该 perm」且「APP 端已在用设备模块」的角色
--      (equipment:master:list / equipment:master:add / equipment:maintain:list 任一有 APP 授权)
--    语义: 5 个 perm 是 sql/105 建的 F 型权限载体 (path=''), 每 perm 只有一行 sys_menu.
-- ------------------------------------------------------------------
SET @perm_list := '''qa:calibration-equipment:list'',''qa:calibration-equipment:add'',
  ''qa:calibration-record:list'',''qa:calibration-record:add'',''qa:calibration:check''';

SET @sql_grant := IF(@has_ct > 0,
  CONCAT('
  INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
  SELECT rm_pc.role_id, m.id, ''APP''
  FROM sys_menu m
  JOIN sys_role_menu rm_pc ON rm_pc.menu_id = m.id AND rm_pc.client_type = ''PC''
  WHERE m.perms IN (', @perm_list, ') AND m.status = 1
    AND EXISTS (
      SELECT 1 FROM sys_role_menu rm_app
      JOIN sys_menu m_app ON m_app.id = rm_app.menu_id
      WHERE rm_app.role_id = rm_pc.role_id AND rm_app.client_type = ''APP''
        AND m_app.perms IN (''equipment:master:list'', ''equipment:master:add'', ''equipment:maintain:list'')
    )
    AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu rm_dup
      WHERE rm_dup.role_id = rm_pc.role_id AND rm_dup.menu_id = m.id AND rm_dup.client_type = ''APP''
    )'),
  CONCAT('
  INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
  SELECT rm_pc.role_id, m.id
  FROM sys_menu m
  JOIN sys_role_menu rm_pc ON rm_pc.menu_id = m.id
  WHERE m.perms IN (', @perm_list, ') AND m.status = 1
    AND EXISTS (
      SELECT 1 FROM sys_role_menu rm_app
      JOIN sys_menu m_app ON m_app.id = rm_app.menu_id
      WHERE rm_app.role_id = rm_pc.role_id
        AND m_app.perms IN (''equipment:master:list'', ''equipment:master:add'', ''equipment:maintain:list'')
    )
    AND NOT EXISTS (
      SELECT 1 FROM sys_role_menu rm_dup
      WHERE rm_dup.role_id = rm_pc.role_id AND rm_dup.menu_id = m.id
    )'));
PREPARE s FROM @sql_grant; EXECUTE s; DEALLOCATE PREPARE s;

-- ------------------------------------------------------------------
-- 3. 校验: 每个 perm 被哪些角色拿到 (APP 端)
-- ------------------------------------------------------------------
SELECT r.role_code, m.perms, rm.client_type
FROM sys_role_menu rm
JOIN sys_role r ON r.id = rm.role_id
JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.perms IN ('qa:calibration-equipment:list', 'qa:calibration-equipment:add',
                  'qa:calibration-record:list', 'qa:calibration-record:add', 'qa:calibration:check')
ORDER BY r.role_code, m.perms, rm.client_type;

-- ------------------------------------------------------------------
-- 4. (仅 canary 用/可选) 灌主库前清掉 canary 上的本次 APP 行, 避免复制撞 1062:
--    DELETE rm FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id
--    WHERE rm.client_type = 'APP' AND m.perms IN (
--      'qa:calibration-equipment:list','qa:calibration-equipment:add',
--      'qa:calibration-record:list','qa:calibration-record:add','qa:calibration:check');
-- ------------------------------------------------------------------
