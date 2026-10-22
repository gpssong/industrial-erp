-- v1.1.114: 补 sales:delivery:add 的 APP 端授权 (App H5 新建销售出库单功能)
--
-- 现象: App H5 销售出库列表页新增「+ 新建销售出库单」按钮 (v1.1.114),
--       但后端 SalDeliveryController.add 的 @SaCheckPermission("sales:delivery:add")
--       只在 PC 端有授权 → App 端提交必然 403.
--
-- 根因 (R18): sql/28 建 sales:delivery:add 菜单节点时只灌了 sys_role_menu 的 PC 行,
--       漏了 APP 行。而 App 端 LoginVO.appMenus 只过滤 client_type='APP' 的授权,
--       PC-only 的行不会进 appMenus → 前端 canAdd 门禁也拿不到 perm.
--
-- 修法: 给 6 内置角色补 sales:delivery:add / :edit / :delete / :check / :uncheck 的 APP 端授权
--       (NOT EXISTS 守门, 幂等, 重复灌安全). 只补 APP, PC 端已由 sql/28 灌过不动.
--
-- 踩坑 (灌 sql 到飞牛容器): 第一次用 `ssh 'docker exec -i <容器> mysql ... < /tmp/125.sql'` 没生效 ——
--       `docker exec -i` 只把**本地 ssh stdin** 接进容器命令, 不处理容器内的 `<` 重定向;
--       而且 sudo 嵌套还会吃掉 stdin 流。正确路径:
--         docker cp /tmp/125.sql <容器>:/tmp/125.sql
--         docker exec <容器> sh -c "mysql -uroot -p*** --default-character-set=utf8mb4 industrial_erp < /tmp/125.sql"
--       灌完必须 SELECT 验证行数, 静默匹配 0 行时 mysql 不报错 (ROW_COUNT() 才是真信号)。
--
-- 验证: 灌完后 App 端重新登录, appMenus 应含 sales:delivery:add → 列表页出现新建按钮。
--       实测灌完 6 角色各 6~7 个 perm (共 37 行 APP 授权)。
USE industrial_erp;

-- 1. 4 个 sales:delivery 写操作 perm × 6 内置角色 补 APP 端授权
INSERT INTO sys_role_menu (role_id, menu_id, client_type)
SELECT r.id, m.id, 'APP'
FROM sys_role r
CROSS JOIN sys_menu m
WHERE m.perms IN (
    'sales:delivery:add',
    'sales:delivery:edit',
    'sales:delivery:delete',
    'sales:delivery:check',
    'sales:delivery:uncheck'
  )
  AND m.deleted = 0
  AND r.role_code IN ('SUPER_ADMIN','PURCHASE_MGR','SALES_MGR','WAREHOUSE_MGR','PRODUCTION_MGR','FINANCE')
  AND r.deleted = 0
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm
    WHERE rm.role_id = r.id AND rm.menu_id = m.id AND rm.client_type = 'APP'
  );

-- 2. 验证: 应看到 6 角色 × 5 perm = 30 行 APP 授权
SELECT r.role_code, m.perms, rm.client_type
FROM sys_role r
JOIN sys_role_menu rm ON rm.role_id = r.id
JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.perms LIKE 'sales:delivery:%'
  AND rm.client_type = 'APP'
ORDER BY r.role_code, m.perms;