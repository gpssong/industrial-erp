-- =============================================================================
-- 23_add_app_button_menus.sql
-- v1.1.8+: App 端"新增商品" / "新增生产加工单"快捷入口需要的按钮菜单
-- 背景: PC 端"角色管理 → 分配权限 → App 端菜单权限"Tab (Role.vue) 通过
--       APP_MENU_WHITELIST 硬编码了 /base/product/add 和 /production/order/add
--       两个 path, 但 sys_menu 表里没有这两条菜单, 导致 buildAppMenuTree()
--       byPath.get(c.path) 返回 undefined, 这两个节点被过滤掉.
--       App 端 dashboard.vue 拿不到这两个 menu.id, 也就无法在 visibleMenus
--       里展示"新增商品"/"生产加工单".
-- 修复: 在 sys_menu 插入 2 条 button 类菜单, parent_id 挂在已有的
--       商品管理 (id=301) 和 生产加工单 (id=702) 下.
--
-- ⚠️ 重要: 用十六进制字面量 (X'...' / 0x...) 传中文, 避免 mysql 客户端
--   在 ssh/管道传输 stdin 时把 UTF-8 字节当 Latin-1 重新编码, 造成双重
--   编码乱码 (典型症状: "新增商品" 显示为 "æ–°å¢žå•†å" ).
--   推荐执行方式:
--     sudo docker exec erp-mysql mysql -uroot -perp_root_pwd industrial_erp \
--       --default-character-set=utf8mb4 < 23_add_app_button_menus.sql
--   或在 MySQL 客户端: SOURCE /path/to/23_add_app_button_menus.sql;
-- =============================================================================

-- 幂等保护: 同一 path 只插入一次
-- 用 _utf8mb4 X'...' (单引号十六进制) 避免 stdin 编码问题
-- "新增商品" UTF-8: E696B0 E5A29E E59586 E59381
INSERT INTO sys_menu (parent_id, menu_name, menu_type, path, perms, is_visible, status, sort_no, deleted)
SELECT 301, _utf8mb4 X'E696B0E5A29EE59586E59381', 'B', '/base/product/add', 'base:product:add', 0, 1, 99, 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/base/product/add' AND deleted = 0);

-- "新增生产加工单" UTF-8: E696B0 E5A29E E7949F E4BAA7 E58AA0 E5B7A5 E58D95
INSERT INTO sys_menu (parent_id, menu_name, menu_type, path, perms, is_visible, status, sort_no, deleted)
SELECT 702, _utf8mb4 X'E696B0E5A29EE7949FE4BAA7E58AA0E5B7A5E58D95', 'B', '/production/order/add', 'production:order:add', 0, 1, 99, 0
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path = '/production/order/add' AND deleted = 0);

-- 验证插入结果
SELECT id, parent_id, menu_name, path, status FROM sys_menu WHERE path IN ('/base/product/add','/production/order/add') AND deleted = 0;
