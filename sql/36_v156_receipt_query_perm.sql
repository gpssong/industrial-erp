-- =====================================================================
-- 三十六、v1.1.56 — 采购入库单查询独立 perm (`purchase:receipt:query`)
-- =====================================================================
--
-- 背景:
--   2026-09-21 用户反馈: gpssong1 (WAREHOUSE_MGR) 有 App 端「采购入库单查询」
--   权限 (PC 端 Role.vue 白名单 app-402-receipt-query, perms=purchase:receipt:list),
--   但 App 端工作台没有「采购入库单」入口.
--
-- 根因 (Phase 1 调研):
--   app/src/pages/dashboard/index.vue 的 APP_MENU_TO_PAGE 按 (perms, path) 双匹配
--   App 快捷入口, 而 sys_menu 里「扫码入库」和「采购入库单查询」都复用
--   sys_menu id=402 (perms=purchase:receipt:list, path=/purchase/receipt) 这一条:
--     L135: { perms:'purchase:receipt:list', path:'/purchase/receipt',
--             page: 扫码入库 /pages/scan/in }
--     L146: { perms:'purchase:receipt:list', path:'/purchase/receipt',
--             page: 采购入库单 /pages/purchase/receipt-list }
--   visibleMenus 用 APP_MENU_TO_PAGE.find() 取第一个匹配, 402 同时命中两条,
--   find() 永远返回 L135 扫码入库 → L146 采购入库单**被永久遮蔽**, 任何非超管
--   账号都只能看到「扫码入库」, 看不到「采购入库单」.
--
--   对比「销售出库单查询」(v1.1.14): 扫码出库走 sys_menu 503 (sales:return:list
--   /sales/return), 销售出库单走 sys_menu 502 (sales:delivery:list /sales/delivery),
--   两条 perms+path 不同, 不撞车, 所以销售两个 App 入口都能显示. 采购只有 402
--   一条 sys_menu 同时服务两个入口, 才撞车.
--
-- 修复 (方案 B — 对齐销售模型, 给「采购入库单查询」独立 perm):
--   1) sys_menu 新增 `purchase:receipt:query` 1 行 perm 载体
--      (F 类型, parent_id=0, is_visible=0, sort_no=2016 紧邻 uncheck 2015)
--   2) 凡是有 purchase:receipt:list APP 授权的角色 → 自动补
--      purchase:receipt:query APP 授权 (沿用 sql/33 模式, "有 list 才有 query",
--      保证 gpssong1 等仓管/制袋工的采购入库单查询入口出现, 扫码入库不受影响)
--   3) App 端 dashboard APP_MENU_TO_PAGE L146 + PC 端 Role.vue 白名单
--      「采购入库单查询」改用 purchase:receipt:query, 与 L135 扫码入库分离
--
-- 后端: 查询端点 (PurReceiptController /page + /{id}) 仍走 purchase:receipt:list
--       (扫码入库 App 也调它), 不动. query perm 纯是**入口显隐**级 perm 载体,
--       与销售的 502/503 双 sys_menu 模型对称 — 前端两个入口各看各的 perm.
--
-- 设计原则 (R5):
--   - 入口级 perm 拆分遵循"同一 perms+path 撞车才拆"; 不批量改造历史 perm
--   - query 与 list 业务语义等同 (都是读), 但**入口不同**, 拆独立 perm 载体
--     只是为了解决 App 端 (perms,path) 匹配撞车, 不是做风险/频率分层
--
-- ⚠️ MySQL 8 容器默认 latin1, 必须:
--   docker exec -i erp-mysql mysql industrial_erp -uroot -p$PW \
--     --default-character-set=utf8mb4 < 36_v156_receipt_query_perm.sql
-- =====================================================================

USE `industrial_erp`;

-- 1. 新增 purchase:receipt:query perm 行 (F 类型, parent_id=0, is_visible=0)
--    sort_no=2016 紧邻 uncheck (2015), 不与 sql/28/34/35 现有 sort_no 冲突
INSERT IGNORE INTO `sys_menu`(`parent_id`,`menu_name`,`menu_type`,`path`,`component`,`perms`,`icon`,`sort_no`,`is_visible`,`status`) VALUES
(0,'采购入库单查询','F','',NULL,'purchase:receipt:query',NULL,2016,0,1);

-- 2. 采购入库单查询: 凡是有 purchase:receipt:list APP 授权的角色, 补 purchase:receipt:query APP 授权
--    沿用 sql/33_v153_scan_in_perm 的 "有 list 才有 add" 模式, 升级成 "有 list 才有 query"
INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`,`client_type`)
SELECT DISTINCT rm.role_id, m.id, 'APP'
FROM `sys_role_menu` rm
JOIN `sys_menu` m ON m.perms = 'purchase:receipt:query' AND m.deleted = 0
WHERE rm.menu_id IN (SELECT id FROM `sys_menu` WHERE perms = 'purchase:receipt:list' AND deleted = 0)
  AND rm.client_type = 'APP';

-- 3. 校验: 角色同时有 list + query (APP 端), 确认 gpssong1/WAREHOUSE_MGR 拿到 query
SELECT r.role_code, r.role_name,
       EXISTS(SELECT 1 FROM `sys_role_menu` rm JOIN `sys_menu` m ON m.id = rm.menu_id
              WHERE rm.role_id = r.id AND m.perms = 'purchase:receipt:list'
                AND rm.client_type = 'APP') AS pur_list_app,
       EXISTS(SELECT 1 FROM `sys_role_menu` rm JOIN `sys_menu` m ON m.id = rm.menu_id
              WHERE rm.role_id = r.id AND m.perms = 'purchase:receipt:query'
                AND rm.client_type = 'APP') AS pur_query_app,
       EXISTS(SELECT 1 FROM `sys_role_menu` rm JOIN `sys_menu` m ON m.id = rm.menu_id
              WHERE rm.role_id = r.id AND m.perms = 'sales:delivery:list'
                AND rm.client_type = 'APP') AS sal_list_app
FROM `sys_role` r
WHERE r.deleted = 0
ORDER BY r.id;

-- 预期:
--   - WAREHOUSE_MGR (gpssong1): pur_list_app=1, pur_query_app=1 (sql/36 自动补)
--   - 制袋工(zdg)/吹膜工(cmg): pur_list_app=1, pur_query_app=1
--   - 没 list APP 的角色: query_app=0 (sql/36 不会自动绑)

-- 4. 用户操作:
--    - 受影响的 App 用户**退出 App 重新登录**一次, Sa-Token session 缓存的
--      perm 数组从 DB 重新加载, 采购入库单查询入口才会出现
--    - 同步重打 App APK (dashboard APP_MENU_TO_PAGE 改了 perms) + pc-web dist
--      (Role.vue 白名单改了 perms), 部署 home + 飞牛
