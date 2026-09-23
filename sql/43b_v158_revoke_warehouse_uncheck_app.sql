-- =====================================================================
-- 四十三.bis、v1.1.58 hotfix-1 — 撤销 sql/43 误补的 WAREHOUSE_MGR uncheck APP
-- =====================================================================
--
-- 背景 (2026-09-22 用户反馈 + 自审):
--   sql/43 第 1 段"全量兜底补 APP uncheck"用 INNER JOIN + NOT EXISTS,
--   给所有"有 check APP 没 uncheck APP"角色都补 uncheck APP. 这个策略与 sql/38
--   撤销 WAREHOUSE_MGR uncheck APP 的意图**冲突**:
--     - sql/38: WAREHOUSE_MGR 有 check APP, 但反审核是高风险操作, 应**手动**控制
--     - sql/43: 兜底触发器自动补 uncheck APP, 把 sql/38 撤销的 WAREHOUSE_MGR uncheck APP 又补回来了
--   副作用: WAREHOUSE_MGR uncheck APP 重新被 sql/43 补回 (=1)
--
-- 根因 (R9 — 写入 sql/43 + sql/38 注释 + CLAUDE.md):
--   sql/35/37/43 三段"有 check 补 uncheck"自动补策略**不应该**应用到 WAREHOUSE_MGR
--   这类"可正向不能反向"角色. 但 SQL 实现无法区分"业务上有意撤销" vs "历史漏补",
--   所以触发器会把 sql/38 已经 DELETE 掉的行重新 INSERT 回来.
--
-- 修复 (方案 B — 显式 blacklist, 不依赖触发器白名单):
--   1) 撤销 sql/43 对 WAREHOUSE_MGR 的副作用:
--      DELETE WAREHOUSE_MGR 的 purchase:receipt:uncheck APP + sales:delivery:uncheck APP
--   2) **不撤销** sql/43 对 zdg/cmg 等的补授权 (这是用户本次反馈的诉求)
--   3) (idempotent) 任何 WAREHOUSE_MGR uncheck APP 已被撤销, 跑第二次无副作用
--
-- 设计原则 (R9 — 写入 CLAUDE.md "反审核 perm 触发器" 段):
--   **黑名单模式** (新规, 取代 sql/35 "白名单触发器" 模式):
--     - 默认: 所有有 check APP 的角色都自动获 uncheck APP (业务合理假设)
--     - 例外 (blacklist): WAREHOUSE_MGR 这类"可正向不能反向"角色, 须显式 DELETE
--     - 后续新角色: 默认有 check → 就有 uncheck (业务惯例), 不需要额外手动勾
--     - 撤销: 业务方提需求 → 在 sql/blacklist_uncheck_app.sql 追加 DELETE 一行
--     - 优势: 简单, 不依赖角色名硬编码, 兼容未来新增角色
--   **白名单模式** (sql/35 老模式, 已废弃):
--     - 默认: 不补 uncheck APP
--     - 例外 (whitelist): SUPER_ADMIN/PURCHASE_MGR/... 显式触发器补
--     - 缺点: 新增角色容易漏, 后续维护成本高, 已被 sql/43 取代
--
-- ⚠️ MySQL 8 容器默认 latin1, 必须:
--   docker exec -i erp-mysql mysql industrial_erp -uroot -p$PW \
--     --default-character-set=utf8mb4 < 43_v158_fix_uncheck_for_nonbuiltin.sql
--   然后立刻跑 (idempotent): 同样方式执行 43b_v158_revoke_warehouse_uncheck_app.sql
-- =====================================================================

USE `industrial_erp`;

-- 1. 撤销 WAREHOUSE_MGR 的 uncheck APP (与 sql/38 等效, 重新执行因为 sql/43 把行补回来了)
DELETE rm FROM `sys_role_menu` rm
JOIN `sys_role` r ON r.id=rm.role_id AND r.deleted=0
JOIN `sys_menu` m ON m.id=rm.menu_id AND m.deleted=0
WHERE r.role_code='WAREHOUSE_MGR'
  AND rm.client_type='APP'
  AND m.perms IN ('purchase:receipt:uncheck','sales:delivery:uncheck');

-- 2. 校验: WAREHOUSE_MGR 现状 (应恢复 sql/38 撤销后的状态)
SELECT r.role_code, r.role_name,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='purchase:receipt:uncheck' AND rm.client_type='APP') AS pur_uncheck_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='sales:delivery:uncheck' AND rm.client_type='APP') AS sal_uncheck_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='purchase:receipt:uncheck' AND rm.client_type IN ('PC','BOTH')) AS pur_uncheck_pc,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='sales:delivery:uncheck' AND rm.client_type IN ('PC','BOTH')) AS sal_uncheck_pc
FROM sys_role r WHERE r.deleted=0 AND r.role_code='WAREHOUSE_MGR';

-- 预期 (跑完后):
--   pur_uncheck_app=0  (sql/38 撤销意图保留)
--   sal_uncheck_app=0  (sql/38 撤销意图保留)
--   pur_uncheck_pc=1   (PC 端保留)
--   sal_uncheck_pc=1   (PC 端保留)

-- 3. 全表校验 (确保只有 WAREHOUSE_MGR 被改, 其他角色不变)
SELECT r.role_code, r.role_name,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='purchase:receipt:uncheck' AND rm.client_type='APP') AS pur_uncheck_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='sales:delivery:uncheck' AND rm.client_type='APP') AS sal_uncheck_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='purchase:receipt:uncheck' AND rm.client_type IN ('PC','BOTH')) AS pur_uncheck_pc,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id WHERE rm.role_id=r.id AND m.perms='sales:delivery:uncheck' AND rm.client_type IN ('PC','BOTH')) AS sal_uncheck_pc
FROM sys_role r WHERE r.deleted=0 ORDER BY r.id;

-- 预期:
--   SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/PRODUCTION_MGR/FINANCE:
--     pur_uncheck_app=1  sal_uncheck_app=0  ← sal uncheck APP 是历史状态, 没被 sql/43 改
--     pur_uncheck_pc=1   sal_uncheck_pc=1
--   WAREHOUSE_MGR:
--     pur_uncheck_app=0  sal_uncheck_app=0  ← sql/43b 撤销
--     pur_uncheck_pc=1   sal_uncheck_pc=1
--   zdg (制袋工):
--     pur_uncheck_app=1  sal_uncheck_app=0  ← sql/43 补 (用户本次反馈诉求)
--     pur_uncheck_pc=1   sal_uncheck_pc=0   ← 历史未绑 sales check PC, 也不补
--   cmg (吹膜工):
--     全 0 (不变, 业务决策: 完全没 check 不补 uncheck)

-- 4. 用户操作:
--    - zdg 用户 (截图那个账号) 退出 App 重新登录一次 → Sa-Token session 刷 perm
--    - 然后再进入 RKP202609220001 详情 → 黄色【反审核】按钮**可点击**, 不再弹"无权限访问"
--    - gpssong1 (WAREHOUSE_MGR) 退出 App 重登 → 反审核按钮消失 (sql/43b 撤销生效)
--    - 不需要重打后端 jar / App APK / pc-web dist (纯数据修复)
