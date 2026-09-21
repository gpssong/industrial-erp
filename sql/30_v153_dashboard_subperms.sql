-- =====================================================================
-- 三十、v1.1.53 — 工作台权限细粒度拆分 (PC + App 双端)
-- =====================================================================
--
-- 背景:
--   工作台 (PC: dashboard/Index.vue / App: dashboard/index.vue) 原本一刀切
--   用一个 report:view 权限码控制整个页面. 老板/经理/主管/仓管共用同一个
--   视图, 仓库主管被迫也能看到销售金额等敏感数据.
--
--   本脚本把工作台拆成 4 个独立可选子模块:
--     1. dashboard:kpi              — 销售指标 (4 个 KPI 卡片)
--     2. dashboard:sales-trend      — 销售趋势 (近30天折线图)
--     3. dashboard:sales-ranking    — 销售排行 TOP10
--     4. inventory:warning:list     — 库存预警 (复用 sql/28 已建行, 不重建)
--
--   报表中心菜单 (sys_menu.id=9, 销售报表/库存报表子页面) 继续由 report:view
--   控制 — 工作台 vs 报表中心是两个不同菜单, 不要混淆.
--
-- 约定:
--   - menu_type='F' parent_id=0 is_visible=0 (不出现在菜单树)
--   - perms 是单值 (不能逗号分隔, schema 是 VARCHAR(128) 单权限码)
--   - INSERT IGNORE 可重入
-- =====================================================================

USE `industrial_erp`;

-- ⚠️ MySQL 8 容器默认字符集 latin1, 跑本脚本必须显式指定 utf8mb4:
--   docker exec -i erp-mysql mysql industrial_erp -uroot -p$PW --default-character-set=utf8mb4 < 30_v153_dashboard_subperms.sql
-- 否则 menu_name 会以 latin1 字节写入, 渲染成 mojibake (é”€å”®æŒ‡æ ‡).
-- 如果已踩坑, 用 perms 定位行直接 UPDATE (按 perms 不受字符集影响):
--   UPDATE sys_menu SET menu_name='销售指标' WHERE perms='dashboard:kpi';
--   UPDATE sys_menu SET menu_name='销售趋势' WHERE perms='dashboard:sales-trend';
--   UPDATE sys_menu SET menu_name='销售排行' WHERE perms='dashboard:sales-ranking';

-- 1. 新增 3 行 sys_menu (KPI/趋势/排行)
-- 注: id 留空走 AUTO_INCREMENT 雪花 ID; sort_no 用 3001/3002/3003 沿用 F 类型约定
-- 父菜单 = 工作台 (id=1, sys_menu 表里 path=/dashboard 的那个根).
-- 必须在工作台下显示才能让用户在角色授权页 (Role.vue) 看到这 3 行
-- (否则 parent_id=0 会变成 root level,el-tree 渲染时挤在工作台/system管理等同级,
--  不会折叠在工作台下,用户找不到).
-- 库存预警 (inventory:warning:list, sql/28 创建) 历史 parent_id=0,也需要 UPDATE 到 1.
INSERT IGNORE INTO `sys_menu`(`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
(1, '销售指标',  'F', '', NULL, 'dashboard:kpi',           NULL, 3001, 0, 1),
(1, '销售趋势',  'F', '', NULL, 'dashboard:sales-trend',   NULL, 3002, 0, 1),
(1, '销售排行',  'F', '', NULL, 'dashboard:sales-ranking', NULL, 3003, 0, 1);

-- 1b. (idempotent) 把已存在的 4 行 F-type perm 移到工作台下, 兼容 sql/28 单独跑过的环境
UPDATE sys_menu SET parent_id = 1 WHERE perms IN ('dashboard:kpi','dashboard:sales-trend','dashboard:sales-ranking','inventory:warning:list') AND parent_id = 0;

-- 2. 内置 6 个角色都绑定这 3 个新 perm (PC client_type)
--    之前这些角色都绑了 report:view, 现在拆细后需要各自显式绑子模块 perm.
--    库存预警 (inventory:warning:list) 已在 sql/28 里给这 6 角色绑过, 不重复.
INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`,`client_type`)
SELECT r.id, m.id, 'PC'
FROM sys_role r
CROSS JOIN sys_menu m
WHERE m.perms IN ('dashboard:kpi','dashboard:sales-trend','dashboard:sales-ranking')
  AND r.deleted = 0 AND m.deleted = 0;

-- 3. 清理孤儿: dashboard:view 这个 perm 在 sys_menu 里从来就没有对应行,
--    之前 backend ReportController.java:32-33 的 requirePerm("dashboard:view")
--    实际没人能过 (只有 super_admin 走 isSuperAdmin() 短路), 是设计残留.
--    本次顺便把这个幽灵权限从代码里去掉 (见 backend 改动).
--    sql 层无 sys_menu 行需要清理.
