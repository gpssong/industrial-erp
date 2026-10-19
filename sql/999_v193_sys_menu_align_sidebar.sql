-- ============================================================================
-- v1.1.93  菜单管理对齐实际侧边栏 (4 组): 经营总览 / 供应链 / 生产与质量 / 经营支持
-- ----------------------------------------------------------------------------
-- 背景: 左侧侧边栏 (MainLayout.vue menuTree) 是硬编码 4 组结构; 菜单管理渲染的是
--       sys_menu 活表, 早期残留 9 个旧顶层目录 (id 1-9: 工作台/系统管理/基础资料/
--       采购管理/销售管理/库存管理/生产管理/财务/报表中心) 与侧边栏对不上, 看着"没用"。
--
-- 策略 (低风险, 保住权限): **不物理删任何带 perms 的行, 也不动 sys_role_menu**。
--   1) 旧 9 目录 (1-9) 从不被角色直接授权 (sys_role_menu 0 行指向它们), 复用上提
--      为 4 组新根: 复用 1/3/4/9 四个 id 作为 4 个新组根, 其余 5/6/7/8 停用隐藏。
--   2) 旧叶子 M/C (301-305 基础 / 401-403 采购 / 501-503 销售 / 601-603 库存 /
--      701-702 生产 / 801,6036 财务 / 901,902,352,353 报表 / 201-205,605 系统)
--      全部保留 (id 不变 → 角色授权零影响), 只 UPDATE parent_id 挂到新 4 组。
--   3) XK/MES 页 (采购入库 402 / 库存与追溯 / 设备维保 / 质量XK / 单据打印 / 审计归档
--      / 合格供应商 / 购销存 hub) 新建 M 行挂 4 组 (幂等 WHERE NOT EXISTS, 按 path 查重)。
--   4) 全部带 perms 的 F/C 权限载体行不动 (角色权限树依赖它们)。
-- 幂等: 可重复执行 (UPDATE 天然幂等; INSERT 走 WHERE NOT EXISTS; 停用用 UPDATE)。
-- 执行: mysql --default-character-set=utf8mb4 ... (飞牛 canary 先行, home 待批准)
-- ============================================================================

-- 0) 安全备份 (可回滚)
CREATE TABLE IF NOT EXISTS sys_menu_bak_v193 AS SELECT * FROM sys_menu;
CREATE TABLE IF NOT EXISTS sys_role_menu_bak_v193 AS SELECT * FROM sys_role_menu;

-- ----------------------------------------------------------------------------
-- 1) 复用 4 个旧目录 id 作为新 4 组根 (改名/改 path, 其余属性保留)
--    1=工作台→经营总览   3=基础资料→供应链   4=采购管理→生产与质量   9=报表中心→经营支持
-- ----------------------------------------------------------------------------
UPDATE sys_menu SET menu_name='经营总览',   path='/_grp/overview',   icon='Odometer',  is_visible=1, status=1 WHERE id=1;
UPDATE sys_menu SET menu_name='供应链',     path='/_grp/supply',     icon='Connection', is_visible=1, status=1 WHERE id=3;
UPDATE sys_menu SET menu_name='生产与质量',  path='/_grp/production', icon='Cpu',      is_visible=1, status=1 WHERE id=4;
UPDATE sys_menu SET menu_name='经营支持',    path='/_grp/support',    icon='Tools',     is_visible=1, status=1 WHERE id=9;

-- ----------------------------------------------------------------------------
-- 2) 停用 (隐藏) 另外 5 个旧目录, 不物理删 (防误删, 保留可恢复)
--    2=系统管理 5=销售管理 6=库存管理 7=生产管理 8=财务  → is_visible=0,status=0
--    其下叶子在第 3 步已重挂到 4 组, 空目录自然隐藏
-- ----------------------------------------------------------------------------
UPDATE sys_menu SET is_visible=0, status=0 WHERE id IN (2,5,6,7,8);

-- ----------------------------------------------------------------------------
-- 3) 旧叶子 M/C 重挂到 4 组 (parent_id 更新; id 不变, 授权不受影响)
--    供应链(3): 基础资料叶子 301-305 + 采购 401-403 + 销售 501-503
--    生产与质量(4): 库存 601-603 + 生产 701-702 + 设备安全 workflow 354
--    经营支持(9): 财务 801,6036 + 报表 901,902,352,353 + 系统 201-205,605 + 飞鹅 952
-- ----------------------------------------------------------------------------
UPDATE sys_menu SET parent_id=3 WHERE id IN (301,302,303,304,305, 401,402,403, 501,502,503);
UPDATE sys_menu SET parent_id=4 WHERE id IN (601,602,603, 701,702, 2090345792472715354);
UPDATE sys_menu SET parent_id=9 WHERE id IN (801,6036, 901,902,2090345792472715352,2090345792472715353, 201,202,203,204,205,605, 952,2090345792472715265);
-- 经营总览(1): 保留飞鹅 952 子项? 不, 飞鹅归经营支持。经营总览只放 /dashboard 自身 (id=1 复用后)。

-- workflow 354 自身带 work:maintain:list, 子项 355-358 (租客/维保/安全/应急) 已 parent_id=354, 随 354 一起进 生产与质量, 无需再动。

-- ----------------------------------------------------------------------------
-- 4) 新建 XK/MES 侧边栏专属叶子 (幂等, 按 path 查重; 都是真路由, 非权限载体, status=1,is_visible=1,client_type=BOTH)
-- ----------------------------------------------------------------------------
-- 供应链组 (parent_id=3)
INSERT INTO sys_menu (id,parent_id,menu_name,menu_type,path,component,perms,icon,sort_no,is_visible,status,client_type,deleted,create_time,update_time)
SELECT 3201,3,'购销存管理','M','/erp','','Document',1,1,'BOTH',0,NOW(),NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/erp');
INSERT INTO sys_menu (id,parent_id,menu_name,menu_type,path,component,perms,icon,sort_no,is_visible,status,client_type,deleted,create_time,update_time)
SELECT 3202,3,'合格供应商','M','/xk/material','','xk:material:list',2,1,'BOTH',0,NOW(),NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/xk/material');
-- 采购入库 402 已存在 (parent_id 第 3 步已改到 3), 不重复建

-- 生产与质量组 (parent_id=4)
INSERT INTO sys_menu (id,parent_id,menu_name,menu_type,path,component,perms,icon,sort_no,is_visible,status,client_type,deleted,create_time,update_time)
SELECT 3211,4,'领料记录','M','/production/requisition','','production:requisition:list',3,1,'BOTH',0,NOW(),NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/production/requisition');
INSERT INTO sys_menu (id,parent_id,menu_name,menu_type,path,component,perms,icon,sort_no,is_visible,status,client_type,deleted,create_time,update_time)
SELECT 3212,4,'成品入库','M','/production/finished-in','','production:finished-in:list',4,1,'BOTH',0,NOW(),NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/production/finished-in');
INSERT INTO sys_menu (id,parent_id,menu_name,menu_type,path,component,perms,icon,sort_no,is_visible,status,client_type,deleted,create_time,update_time)
SELECT 3213,4,'设备维保','M','/xk/maintenance','','work:maintain:list',5,1,'BOTH',0,NOW(),NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/xk/maintenance');
INSERT INTO sys_menu (id,parent_id,menu_name,menu_type,path,component,perms,icon,sort_no,is_visible,status,client_type,deleted,create_time,update_time)
SELECT 3214,4,'质量与XK检查','M','/xk/safety','','work:safety:list',6,1,'BOTH',0,NOW(),NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/xk/safety');
INSERT INTO sys_menu (id,parent_id,menu_name,menu_type,path,component,perms,icon,sort_no,is_visible,status,client_type,deleted,create_time,update_time)
SELECT 3215,4,'库存与追溯','M','/xk/trace','','xk:trace:list',7,1,'BOTH',0,NOW(),NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/xk/trace');

-- 经营支持组 (parent_id=9)
INSERT INTO sys_menu (id,parent_id,menu_name,menu_type,path,component,perms,icon,sort_no,is_visible,status,client_type,deleted,create_time,update_time)
SELECT 3221,9,'单据与打印','M','/xk/print','','system:print:list',8,1,'BOTH',0,NOW(),NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/xk/print');
INSERT INTO sys_menu (id,parent_id,menu_name,menu_type,path,component,perms,icon,sort_no,is_visible,status,client_type,deleted,create_time,update_time)
SELECT 3222,9,'审计归档','M','/xk/audit','','report:view',9,1,'BOTH',0,NOW(),NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE path='/xk/audit');

-- 经营总览组 (parent_id=1)
-- /dashboard 即旧 id=1 (工作台) 本身, 复用后即为 经营总览 组的代表项; 侧边栏 经营驾驶舱 直连 /dashboard。

-- ----------------------------------------------------------------------------
-- 5) 排序 (sort_no) — 让 4 组内顺序合理
-- ----------------------------------------------------------------------------
UPDATE sys_menu SET sort_no=1 WHERE id=1;
UPDATE sys_menu SET sort_no=2 WHERE id=3;
UPDATE sys_menu SET sort_no=3 WHERE id=4;
UPDATE sys_menu SET sort_no=4 WHERE id=9;

-- ----------------------------------------------------------------------------
-- 校验 (只读; 执行后跑一遍确认)
-- ----------------------------------------------------------------------------
-- SELECT menu_name, path, is_visible, status FROM sys_menu WHERE id IN (1,2,3,4,5,6,7,8,9);
-- SELECT COUNT(*) AS leaf_reparented FROM sys_menu WHERE deleted=0 AND parent_id IN (3,4,9) AND id BETWEEN 301 AND 952;
-- SELECT COUNT(*) AS perms_intact FROM sys_menu WHERE deleted=0 AND menu_type IN ('F','C') AND perms<>'' AND perms IS NOT NULL;
-- SELECT rm.menu_id FROM sys_role_menu rm LEFT JOIN sys_menu m ON m.id=rm.menu_id WHERE m.id IS NULL;  -- 应为 0 行
