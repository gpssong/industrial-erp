-- =====================================================================
-- 四十、v1.1.56 hotfix — PC 端【分配权限】弹窗菜单树 parent_id 清理
-- =====================================================================
--
-- 背景 (2026-09-22 用户反馈):
--   PC 端【分配权限】弹窗里 root 顶层堆了一堆孤立的 F 类型 perm 字符串:
--     - dashboard:kpi / dashboard:sales-trend / dashboard:sales-ranking
--       / inventory:warning:list (sql/30 已挂工作台, 已生效, 这里 idempotent 兜底)
--     - purchase:receipt:check / purchase:receipt:uncheck / purchase:receipt:query
--       / purchase:receipt:add (sql/28/35/36 seed 时 parent_id=0)
--     - sales:delivery:check / sales:delivery:uncheck / sales:delivery:add
--       (sql/28/35 同问题)
--     - report:view (sql/seed/14 seed 时 parent_id=0)
--     - production:order:feie-print (sql/20 seed 时 parent_id=0)
--   这些 perm 行出现在 el-tree 顶层, 跟"工作台/系统管理/采购管理"目录并列,
--   弹窗看着极不专业.
--
-- 根因:
--   PC 端 Role.vue 的 menuApi.list() 直接拉 SELECT * FROM sys_menu,
--   没任何 client_type / is_visible / parent_id 过滤, sys_menu 里 F 类型
--   perm 载体跟 M/C 业务菜单节点一锅端, 渲染时按 parent_id 父子嵌套,
--   parent_id=0 全部堆 root.
--
-- 修复 (方案 A — 路线 A, SQL-only):
--   1) sys_menu UPDATE: 把所有根层 F 类型 perm 载体的 parent_id 改到
--      业务对应目录节点下, sort_no 紧贴业务目录的 C 类型菜单节点.
--   2) 业务影响: 弹窗树结构变化; sys_role_menu 不动 (授权关系不变);
--      后端 @SaCheckPermission / requirePerm 完全不受影响.
--   3) 不动后端代码, 不动前端代码, 不重打 pc-web dist.
--
-- 父目录 ID 表:
--   - 工作台              id=1   (path=/dashboard)
--   - 系统管理            id=2   (path=/system)
--   - 采购管理            id=4   (path=/purchase)
--   - 销售管理            id=5   (path=/sales)
--   - 库存管理            id=6   (path=/inventory)
--   - 生产管理            id=7   (path=/production)
--   - 报表中心            id=9   (path=/report)
--
-- 父目录业务菜单 (C 类型, 有 path, 已有):
--   - 采购入库 (path=/purchase/receipt) id=402
--   - 销售出库 (path=/sales/delivery)   id=502
--   - 生产加工单 (path=/production/order) id=702
--   - 库存预警 (path='')               sort_no=4011 (sql/28 已建)
--
-- ⚠️ MySQL 8 容器默认 latin1, 必须:
--   docker exec -i erp-mysql mysql industrial_erp -uroot -p$PW \
--     --default-character-set=utf8mb4 < 40_v156_menu_pc_cleanup.sql
-- =====================================================================

USE `industrial_erp`;

-- 1. 工作台下属 perm 载体 (idempotent 兜底, sql/30 已挂, 双重保险)
UPDATE sys_menu
   SET parent_id = 1
 WHERE deleted = 0
   AND parent_id = 0
   AND perms IN (
     'dashboard:kpi',
     'dashboard:sales-trend',
     'dashboard:sales-ranking',
     'inventory:warning:list'
   );

-- 2. 采购入库 (id=402) 下属 perm 载体
UPDATE sys_menu
   SET parent_id = 402
 WHERE deleted = 0
   AND parent_id = 0
   AND perms IN (
     'purchase:receipt:add',         -- sql/28:43 sort_no=2011
     'purchase:receipt:check',       -- sql/28:46 sort_no=2014
     'purchase:receipt:uncheck',     -- sql/35:64 sort_no=2015
     'purchase:receipt:query'        -- sql/36:55 sort_no=2016
   );

-- 3. 销售出库 (id=502) 下属 perm 载体
UPDATE sys_menu
   SET parent_id = 502
 WHERE deleted = 0
   AND parent_id = 0
   AND perms IN (
     'sales:delivery:add',           -- sql/28:30 sort_no=1011
     'sales:delivery:check',         -- sql/28:33 sort_no=1014
     'sales:delivery:uncheck'        -- sql/35:65 sort_no=1015
   );

-- 4. 报表中心 (id=9) 下属 perm 载体
UPDATE sys_menu
   SET parent_id = 9
 WHERE deleted = 0
   AND parent_id = 0
   AND perms = 'report:view';        -- sql/seed/14:14 sort_no=951

-- 5. 生产加工单 (id=702) 下属 perm 载体
UPDATE sys_menu
   SET parent_id = 702
 WHERE deleted = 0
   AND parent_id = 0
   AND perms = 'production:order:feie-print'; -- sql/20:45

-- 6. 校验: 列出所有 parent_id=0 且 menu_type='F' 的 perm 载体 (目标: 空集)
--    注意 sys_menu 里 parent_id=0 的合法项应是 root 目录 (M 类型),
--    或者历史遗留的没挂对地方的纯 perm 载体 (留待人工处理)
SELECT m.id, m.parent_id, m.menu_name, m.menu_type, m.perms, m.sort_no
FROM sys_menu m
WHERE m.deleted = 0
  AND m.parent_id = 0
  AND m.menu_type = 'F'
ORDER BY m.sort_no;

-- 预期: 0 行 (所有 F 类型 perm 载体已挂到业务目录下)

-- 7. 校验: 工作台 / 采购入库 / 销售出库 / 报表中心 / 生产加工单下挂的 perm 载体
SELECT m2.id AS parent_id, m2.menu_name AS parent_name,
       m1.id, m1.menu_name, m1.perms, m1.sort_no
FROM sys_menu m1
JOIN sys_menu m2 ON m2.id = m1.parent_id
WHERE m1.deleted = 0
  AND m1.menu_type = 'F'
  AND m2.id IN (1, 402, 502, 9, 702)
ORDER BY m2.id, m1.sort_no;

-- 预期:
--   工作台(1):         销售指标 / 销售趋势 / 销售排行 / 库存预警
--   采购入库(402):     采购入库新增 / 采购入库审核 / 采购入库反审核 / 采购入库单查询
--   销售出库(502):     销售出库新增 / 销售出库审核 / 销售出库反审核
--   报表中心(9):       报表查看
--   生产加工单(702):   飞鹅打印

-- 8. 校验: sys_role_menu 数量不变 (本脚本只改 sys_menu.parent_id, 不动授权)
SELECT COUNT(*) AS role_menu_total FROM sys_role_menu;

-- 9. 用户操作:
--    - PC 端刷新【角色管理 → 分配权限】弹窗, 工作台/采购入库/销售出库/报表中心/
--      生产加工单目录下会出现对应 perm 行, root 顶层 perm 字符串清零.
--    - 不需要重打 pc-web dist, 后端 @SaCheckPermission / requirePerm 不受影响.
--    - 不需要 App 用户退出重登 (App 端走 APP_MENU_WHITELIST, 不读 sys_menu
--      parent_id 字段).