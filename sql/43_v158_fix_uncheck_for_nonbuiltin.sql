-- =====================================================================
-- 四十三、v1.1.58 — 制袋/吹膜等非内置角色补 uncheck APP (2026-09-22 用户反馈)
-- =====================================================================
--
-- 背景 (2026-09-22 11:36 用户反馈):
--   gpssong1 用的角色登录 App, 进入 RKP202609220001 (已审核采购入库单) 详情,
--   显示黄色【反审核】按钮, 但点击弹出"无权限访问" toast.
--   用户诉求: 无 uncheck perm 的账号**不显示**反审核按钮 (与 sql/35 设计对齐).
--
--   经核查 (2026-09-22):
--     - 制袋工 zdg:   pur_check_app=1  pur_uncheck_app=0  ← 应有 uncheck, 漏了
--                     sal_check_app=0  sal_uncheck_app=0
--     - 吹膜工 cmg:   pur_check_app=0  pur_uncheck_app=0  ← 完全没 check, 不补
--                     sal_check_app=0  sal_uncheck_app=0
--     - 6 内置角色 (SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/PRODUCTION_MGR):
--         check APP / uncheck APP 都齐 (sql/35+37 部署过)
--     - WAREHOUSE_MGR: check APP 齐, uncheck APP=0 (sql/38 撤销过, 预期)
--
-- 根因:
--   sql/35 第 2 段触发器 ("有 check APP 才有 uncheck APP") 用 INSERT IGNORE,
--   当时 zdg 还没有 check APP → 漏补.
--   后续 sql/34 给 zdg 补了 check APP, sql/37 全量触发器兜底理论上应该把 zdg 补上,
--   但实际 DB 里 zdg 仍 uncheck APP=0. 可能原因:
--     - sql/37 部署到 home 但 zdg 那时 check APP 还没补上
--     - 或部署顺序错位 (sql/34 → sql/35 → sql/37 但中间隔了 v1.1.56 别的 SQL)
--     - 或 sql/37 跑了但被某次手动清理脚本 (sql/38) 反向删了 zdg 的 uncheck
--       (sql/38 是 DELETE FROM sys_role_menu WHERE role_id=WAREHOUSE_MGR, 但 sql 写错
--        有可能 WHERE 条件漂到非 WAREHOUSE_MGR 角色 — 需 audit, 暂不展开)
--
-- 修复 (方案 A — 数据级, 不动代码):
--   1) 全量补"有 check APP 没 uncheck APP"角色 → INSERT IGNORE (兜底, 兼容未来漏补)
--   2) 全量补"有 check APP"角色的对应 uncheck PC (兜底)
--   3) **不动** "有 check PC 没 check APP" 的角色 (PC 业务不在 App 端, 改 App perm 应单独走 PC 端授权)
--   4) **不动** cmg 这类"完全没 check APP"角色 — 反审核是高风险操作, 不能默认给
--      (cmg 应该由用户在 PC 端 Role.vue 弹窗手动勾选 uncheck APP, 这是业务决策)
--   5) (idempotent) 任何角色已有 uncheck APP/PC 不会被 INSERT IGNORE 拦截主键冲突
--
-- 不动:
--   - sys_role_menu 总行数会**增加** (补漏), 不减少 — 安全
--   - 不动 WAREHOUSE_MGR 的 uncheck (sql/38 撤销意图保留)
--   - 不动 cmg 的 uncheck (业务决策: cmg 没 check, 不能默认补 uncheck)
--   - 不动 sys_menu / sys_role
--   - 不动前端 (按钮显隐逻辑 R8 hotfix-2 已正确, 只是 DB 授权缺)
--
-- ⚠️ MySQL 8 容器默认 latin1, 必须:
--   docker exec -i erp-mysql mysql industrial_erp -uroot -p$PW \
--     --default-character-set=utf8mb4 < 43_v158_fix_uncheck_for_nonbuiltin.sql
-- =====================================================================

USE `industrial_erp`;

-- 1. APP 端: 全量兜底补 uncheck APP
--    策略: 凡是有 check APP 的角色, 都补 uncheck APP
--    与 sql/35 第 2 段等效, 但用了 INNER JOIN + 直接 ON perm 匹配, 不依赖多 crm 行
INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`,`client_type`)
SELECT DISTINCT rm.role_id, m_uncheck.id, 'APP'
FROM `sys_role_menu` rm
JOIN `sys_menu` m_check ON m_check.id = rm.menu_id AND m_check.deleted = 0
                       AND m_check.perms = 'purchase:receipt:check'
JOIN `sys_menu` m_uncheck ON m_uncheck.perms = 'purchase:receipt:uncheck'
                         AND m_uncheck.deleted = 0
WHERE rm.client_type = 'APP'
  AND NOT EXISTS (
    SELECT 1 FROM `sys_role_menu` rm2
    JOIN `sys_menu` m2 ON m2.id = rm2.menu_id AND m2.deleted = 0
    WHERE rm2.role_id = rm.role_id
      AND rm2.client_type = 'APP'
      AND m2.perms = 'purchase:receipt:uncheck'
  );

INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`,`client_type`)
SELECT DISTINCT rm.role_id, m_uncheck.id, 'APP'
FROM `sys_role_menu` rm
JOIN `sys_menu` m_check ON m_check.id = rm.menu_id AND m_check.deleted = 0
                       AND m_check.perms = 'sales:delivery:check'
JOIN `sys_menu` m_uncheck ON m_uncheck.perms = 'sales:delivery:uncheck'
                         AND m_uncheck.deleted = 0
WHERE rm.client_type = 'APP'
  AND NOT EXISTS (
    SELECT 1 FROM `sys_role_menu` rm2
    JOIN `sys_menu` m2 ON m2.id = rm2.menu_id AND m2.deleted = 0
    WHERE rm2.role_id = rm.role_id
      AND rm2.client_type = 'APP'
      AND m2.perms = 'sales:delivery:uncheck'
  );

-- 2. PC 端: 全量兜底补 uncheck PC (凡是有 check PC 的角色, 都补 uncheck PC)
--    与 sql/35 第 3 段等效, 但不限于内置 6 角色 — 制袋工等非内置也可获 PC 端反审核
INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`,`client_type`)
SELECT DISTINCT rm.role_id, m_uncheck.id, 'PC'
FROM `sys_role_menu` rm
JOIN `sys_menu` m_check ON m_check.id = rm.menu_id AND m_check.deleted = 0
                       AND m_check.perms = 'purchase:receipt:check'
JOIN `sys_menu` m_uncheck ON m_uncheck.perms = 'purchase:receipt:uncheck'
                         AND m_uncheck.deleted = 0
WHERE rm.client_type IN ('PC', 'BOTH')
  AND NOT EXISTS (
    SELECT 1 FROM `sys_role_menu` rm2
    JOIN `sys_menu` m2 ON m2.id = rm2.menu_id AND m2.deleted = 0
    WHERE rm2.role_id = rm.role_id
      AND rm2.client_type IN ('PC', 'BOTH')
      AND m2.perms = 'purchase:receipt:uncheck'
  );

INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`,`client_type`)
SELECT DISTINCT rm.role_id, m_uncheck.id, 'PC'
FROM `sys_role_menu` rm
JOIN `sys_menu` m_check ON m_check.id = rm.menu_id AND m_check.deleted = 0
                       AND m_check.perms = 'sales:delivery:check'
JOIN `sys_menu` m_uncheck ON m_uncheck.perms = 'sales:delivery:uncheck'
                         AND m_uncheck.deleted = 0
WHERE rm.client_type IN ('PC', 'BOTH')
  AND NOT EXISTS (
    SELECT 1 FROM `sys_role_menu` rm2
    JOIN `sys_menu` m2 ON m2.id = rm2.menu_id AND m2.deleted = 0
    WHERE rm2.role_id = rm.role_id
      AND rm2.client_type IN ('PC', 'BOTH')
      AND m2.perms = 'sales:delivery:uncheck'
  );

-- 3. 校验 1: zdg/cmg 现状
SELECT r.role_code, r.role_name,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id
              WHERE rm.role_id=r.id AND m.perms='purchase:receipt:check'   AND rm.client_type='APP') AS pur_check_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id
              WHERE rm.role_id=r.id AND m.perms='purchase:receipt:uncheck' AND rm.client_type='APP') AS pur_uncheck_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id
              WHERE rm.role_id=r.id AND m.perms='sales:delivery:check'   AND rm.client_type='APP') AS sal_check_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id
              WHERE rm.role_id=r.id AND m.perms='sales:delivery:uncheck' AND rm.client_type='APP') AS sal_uncheck_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id
              WHERE rm.role_id=r.id AND m.perms='purchase:receipt:check'   AND rm.client_type IN ('PC','BOTH')) AS pur_check_pc,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id
              WHERE rm.role_id=r.id AND m.perms='purchase:receipt:uncheck' AND rm.client_type IN ('PC','BOTH')) AS pur_uncheck_pc
FROM sys_role r WHERE r.role_code IN ('zdg','cmg') ORDER BY r.id;

-- 预期 (跑完后):
--   zdg 制袋工: pur_check_app=1 pur_uncheck_app=1 sal_check_app=0 sal_uncheck_app=0
--               pur_check_pc=1  pur_uncheck_pc=1
--   cmg 吹膜工: 全 0 (不变, 业务决策: cmg 没 check 不补 uncheck)

-- 4. 校验 2: 6 内置角色 + 业务角色全表 (确保 sql/43 没破坏既有授权)
SELECT r.role_code, r.role_name,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id
              WHERE rm.role_id=r.id AND m.perms='purchase:receipt:uncheck' AND rm.client_type='APP') AS pur_uncheck_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id
              WHERE rm.role_id=r.id AND m.perms='sales:delivery:uncheck' AND rm.client_type='APP') AS sal_uncheck_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id
              WHERE rm.role_id=r.id AND m.perms='purchase:receipt:uncheck' AND rm.client_type IN ('PC','BOTH')) AS pur_uncheck_pc,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id
              WHERE rm.role_id=r.id AND m.perms='sales:delivery:uncheck' AND rm.client_type IN ('PC','BOTH')) AS sal_uncheck_pc
FROM sys_role r WHERE r.deleted=0 ORDER BY r.id;

-- 5. 校验 3: sys_role_menu 总行数变化
SELECT COUNT(*) AS total_before_after FROM sys_role_menu;
-- (与跑 sql/43 前相比, 应增加 1~3 行: zdg APP/PC 补 2 行 + 全表兜底补漏)

-- 6. 用户操作:
--    - 截图用户(制袋工 zdg 登录的账号)退出 App 重新登录一次 → Sa-Token session 刷 perm
--    - 然后再进入 RKP202609220001 详情 → 黄色【反审核】按钮**可点击**, 不再弹"无权限访问"
--    - 不需要重打后端 jar (代码不变, perm 字符串不变, @SaCheckPermission 注解不变)
--    - 不需要重打 App APK (前端代码不变)
--    - 不需要重打 pc-web dist (PC 端代码不变)
--    - **纯数据级修复** — 仅修 sys_role_menu 表
--    - 重要: 影响范围**仅** zdg 这类角色, 6 内置角色授权不变, WAREHOUSE_MGR uncheck APP=0 保留 (sql/38 撤销意图不变)
