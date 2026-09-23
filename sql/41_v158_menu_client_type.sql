-- =====================================================================
-- 四十一、v1.1.56 — sys_menu 加 client_type 列 (BOTH/PC/APP)
-- =====================================================================
--
-- 背景 (2026-09-22 用户反馈):
--   PC 端【分配权限】弹窗里混杂 PC 配置菜单 (飞鹅打印机) + 业务菜单 (采购入库)
--   + 业务 perm 载体 (dashboard:kpi). sys_menu 没有端别字段, 前端没法区分,
--   后端 SysMenuService.listAll() 走 selectList(null) 零过滤, 一锅端.
--
-- 根因:
--   v1.0.10 引入 client_type 时只加在 sys_role_menu (授权端别), 没加在 sys_menu
--   (菜单定义端别). sys_role_menu.client_type 表达"这条授权在哪个端生效",
--   sys_menu 应该有"这条菜单本身在哪个端可见" — 但 schema 漏做.
--
-- 修复:
--   1) sys_menu 加 client_type VARCHAR(8) DEFAULT 'BOTH' 列
--   2) 飞鹅菜单 (system:feie:*) 回填 PC-only (管理员功能, App 端不该见)
--   3) (idempotent) 兜底: 任何 NULL/空 client_type 默认 BOTH
--   4) 不改后端 SysMenuService.listAll() 默认过滤 (按用户决定 Q1=A 最小路线)
--      — 后端接口保持零过滤, 前端 Role.vue 走默认全表渲染 (弹窗已经清爽)
--      — sys_menu.client_type 主要用于未来: (a) pc-web 弹窗端别过滤;
--      (b) 飞牛/独立的 App 端菜单管理; (c) 数据分析"哪些菜单是哪个端独享"
--
-- 与 sys_role_menu.client_type 区别:
--   sys_role_menu.client_type = "这个授权给哪个端" (PC/APP/BOTH 三态合并)
--   sys_menu.client_type       = "这个菜单定义本身给哪个端" (BOTH/PC/APP)
--   两列语义不同: 前者是授权层端别, 后者是菜单层端别. v1.1.56 之前前端混用
--   两端, 通过 MenuMap 的 rm.client_type AS client_type 临时覆盖. 加本表列后,
--   selectMenusByUserId 等 SQL 仍走 rm.client_type alias, 优先用 JOIN 的角色授权端别
--   (这是想要的行为: 返回的菜单带"该用户在该端能看到的授权"标记).
--
-- ⚠️ MySQL 8 容器默认 latin1, 必须:
--   docker exec -i erp-mysql mysql industrial_erp -uroot -p$PW \
--     --default-character-set=utf8mb4 < 41_v158_menu_client_type.sql
-- =====================================================================

USE `industrial_erp`;

-- 1. 加 client_type 列 (idempotent: 重复跑 ALTER 不报错需先检查)
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = 'industrial_erp'
    AND table_name = 'sys_menu'
    AND column_name = 'client_type'
);

SET @sql := IF(@col_exists = 0,
  'ALTER TABLE sys_menu ADD COLUMN client_type VARCHAR(8) DEFAULT ''BOTH'' COMMENT ''菜单端别: BOTH/PC/APP, 默认全端可见'' AFTER status',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 飞鹅菜单回填 PC-only (Q3 决策)
--    包括 system:feie:list/add/edit/delete/log/template + production:order:feie-print (飞鹅云打印按钮)
UPDATE sys_menu SET client_type = 'PC'
WHERE deleted = 0 AND perms LIKE 'system:feie:%';

-- 3. (idempotent) 兜底: 任何 NULL/空 client_type 默认 BOTH
UPDATE sys_menu SET client_type = 'BOTH'
WHERE deleted = 0 AND (client_type IS NULL OR client_type = '' OR client_type NOT IN ('BOTH','PC','APP'));

-- 4. 验证: 按 client_type 分组计数
SELECT client_type, COUNT(*) AS cnt
FROM sys_menu
WHERE deleted = 0
GROUP BY client_type
ORDER BY cnt DESC;

-- 预期:
--   BOTH 95+  (所有 root + 业务目录 + F 类型 perm 行 + dashboard perm 等)
--   PC    8+  (system:feie:* + production:order:feie-print)
--   APP   0   (暂时无 APP-only 菜单, App 端走 APP_MENU_WHITELIST)

-- 5. 验证: 飞鹅菜单已 PC-only
SELECT id, menu_name, menu_type, perms, client_type
FROM sys_menu
WHERE deleted = 0 AND client_type = 'PC'
ORDER BY sort_no;

-- 预期: system:feie:list/add/edit/delete/test/log/template + production:order:feie-print

-- 6. 验证: 关键业务菜单保持 BOTH (PC 弹窗 + App 端按白名单驱动)
SELECT id, menu_name, perms, client_type
FROM sys_menu
WHERE deleted = 0 AND id IN (1, 2, 4, 5, 402, 502, 702, 9);

-- 预期: 工作台/系统管理/采购管理/销售管理/采购入库/销售出库/生产加工单/报表中心 全 BOTH

-- 7. 用户操作:
--    - PC 端弹窗下次刷新会自动展示 sys_menu.client_type 字段 (后端 SysMenuService.listAll()
--      没改过滤逻辑, PC 端弹窗仍显示全部 menu — 但弹窗视觉效果已因 sql/40 + 折叠方案清爽)
--    - sys_menu.client_type 字段已可被后端 service 直接读写, 未来按需加端别过滤接口
--      (例如 GET /system/menu/list-grantable?client=PC) 给前端 Role.vue 按角色类型显示
--    - 不需要重打后端 jar (SysMenu.java 已加字段注解), 但 App /me 接口或菜单查询接口
--      可能需要重打包 — 当前没改任何 service/listAll() 默认行为, **不需要重打后端 jar**
--    - 不需要 App 用户退出重登 (App 端不走 sys_menu.client_type 过滤)