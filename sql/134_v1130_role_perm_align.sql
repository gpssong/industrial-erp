-- v1.1.130: 角色授权树对齐现系统
-- 1) 清理 sys_menu 根级浮动权限点 (parent_id=0 的 B/F 行)
--    - qa:calibration:*  (11) → /xk/safety「质量与XK检查」(10036), 该页第 5 个 Tab 正在调用
--    - pdm:param:* / pdm:sop:* (8) → 生产加工单 (702); pdm:sop:list 仍被 App 工艺 SOP 使用
--    - outsource:*       (4) → 采购订单 (401), 后端 OutsourceController 仍在用
--    只改挂载点, 不删行、不动 status / is_visible / 既有授权。
-- 2) 幂等: 重复执行不会命中 (parent_id 已不为 0)。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS sys_menu_bak_v1131      AS SELECT * FROM sys_menu;
CREATE TABLE IF NOT EXISTS sys_role_menu_bak_v1131 AS SELECT * FROM sys_role_menu;

-- 检验设备校准 → 质量与XK检查
UPDATE sys_menu
   SET parent_id = 10036
 WHERE deleted = 0 AND parent_id = 0 AND perms LIKE 'qa:calibration%';

-- 工艺参数 / SOP → 生产加工单
UPDATE sys_menu
   SET parent_id = 702
 WHERE deleted = 0 AND parent_id = 0 AND (perms LIKE 'pdm:param%' OR perms LIKE 'pdm:sop%');

-- 委外发出 / 委外加工入库 → 采购订单
UPDATE sys_menu
   SET parent_id = 401
 WHERE deleted = 0 AND parent_id = 0 AND perms LIKE 'outsource:%';

-- 校验: 根级 B/F 应为 0 (允许 /workflow 死子树的那 1 个 M 根, status=0)
SELECT 'root_bf_left' AS k, COUNT(*) AS v
  FROM sys_menu
 WHERE deleted = 0 AND parent_id = 0 AND menu_type IN ('B', 'F');

SELECT 'calibration_under_10036' AS k, COUNT(*) AS v
  FROM sys_menu WHERE deleted = 0 AND parent_id = 10036 AND perms LIKE 'qa:calibration%';

SELECT 'pdm_under_702' AS k, COUNT(*) AS v
  FROM sys_menu WHERE deleted = 0 AND parent_id = 702 AND (perms LIKE 'pdm:param%' OR perms LIKE 'pdm:sop%');

SELECT 'outsource_under_401' AS k, COUNT(*) AS v
  FROM sys_menu WHERE deleted = 0 AND parent_id = 401 AND perms LIKE 'outsource:%';

-- 回滚:
--   UPDATE sys_menu SET parent_id = 0 WHERE perms LIKE 'qa:calibration%' AND parent_id = 10036;
--   UPDATE sys_menu SET parent_id = 0 WHERE (perms LIKE 'pdm:param%' OR perms LIKE 'pdm:sop%') AND parent_id = 702;
--   UPDATE sys_menu SET parent_id = 0 WHERE perms LIKE 'outsource:%' AND parent_id = 401;
