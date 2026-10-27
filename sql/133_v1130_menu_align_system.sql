-- =============================================================================
-- v1.1.130  菜单管理对齐现系统 (4 组: 经营总览 / 供应链 / 生产与质量 / 经营支持)
-- -----------------------------------------------------------------------------
-- 背景:
--   PC 左侧导航早已改为 MainLayout.vue 硬编码 4 组 + 购销存管理 Hub 8 张分类卡,
--   而 sys_menu 仍停留在旧 9 个顶层目录 (工作台/系统管理/基础资料/采购管理/销售管理/
--   库存管理/生产管理/财务/报表中心), 并有 3213 成品入库查询 这类散落根节点,
--   菜单管理页面 (/system/menu) 显示的结构与现系统完全对不上.
--
-- 目标结构 (与现导航一致):
--   经营总览
--     └ 经营驾驶舱 /dashboard  (+ 销售指标/趋势/排行, 库存预警)
--   供应链
--     └ 购销存管理 /erp
--        ├ 基础资料   商品/客户/供应商/仓库/计量单位/合格供应商
--        ├ 采购管理   采购订单/采购入库/采购退货/采购建议
--        ├ 销售管理   销售订单/销售出库/销售退货
--        ├ 库存管理   库存查询/库存台账/库存盘点/库存与追溯
--        ├ 财务       应收应付/发票管理
--        ├ 报表中心   销售报表/库存报表/质量报表/设备报表/回收站/AI 助手
--        └ 系统管理   用户/角色/菜单/部门/系统设置/打印模板 (+ 飞鹅打印机 停用)
--   生产与质量
--     ├ 生产管理   BOM清单/生产加工单/领料记录/成品入库/半成品入库/生产排程/吹膜米重采集
--     ├ 设备维保 /xk/maintenance
--     ├ 质量与XK检查 /xk/safety
--     ├ 进货查验台账 /xk/iqc-ledger
--     └ 检验台账 /report/inspection
--   经营支持
--     ├ 单据与打印 /xk/print
--     └ 审计归档 /xk/audit
--
-- 安全策略:
--   1) 不物理删除任何行, 不动 sys_role_menu 已有授权 (只做**新增**授权);
--      所有既有行的 id 保持不变 → 角色授权零影响.
--   2) 旧 9 个顶层目录 (id 1-9) 就地复用为新结构里的分组/容器 (改名 + 改类型 + 改 parent),
--      其中 id=1 工作台 直接变成 经营驾驶舱 页面.
--   3) 无 UI 载体的权限点 (委外/PDM/校准 等) 保持 parent_id=0 不动, 前端 Role.vue 仍可勾选.
--   4) 新增页面行按 router meta.perm 写 perms, 并按"已有该 perms 的角色"回填授权,
--      保证非管理员侧边栏过滤 (MainLayout allowedPaths) 不会因为新行缺少授权而丢菜单.
--   5) 幂等: UPDATE 天然幂等; INSERT 全部走 NOT EXISTS (id / path 双守卫), 可重复执行.
--
-- 执行: mysql --default-character-set=utf8mb4 ... < 133_v1130_menu_align_system.sql
-- 回滚: 见文件末尾 (从 sys_menu_bak_v1130 / sys_role_menu_bak_v1130 恢复)
-- =============================================================================
SET NAMES utf8mb4;
USE industrial_erp;

-- -----------------------------------------------------------------------------
-- 0) 备份 (可回滚)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_menu_bak_v1130 AS SELECT * FROM sys_menu;
CREATE TABLE IF NOT EXISTS sys_role_menu_bak_v1130 AS SELECT * FROM sys_role_menu;

-- -----------------------------------------------------------------------------
-- 1) 4 个顶层分组 (新增, parent_id=0)
-- -----------------------------------------------------------------------------
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status, client_type, deleted, create_time, update_time)
SELECT 10001, 0, '经营总览', 'C', '', '', '', 'Odometer', 1, 1, 1, 'BOTH', 0, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 10001 OR (parent_id = 0 AND menu_name = '经营总览'));

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status, client_type, deleted, create_time, update_time)
SELECT 10002, 0, '供应链', 'C', '', '', '', 'Connection', 2, 1, 1, 'BOTH', 0, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 10002 OR (parent_id = 0 AND menu_name = '供应链'));

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status, client_type, deleted, create_time, update_time)
SELECT 10003, 0, '生产与质量', 'C', '', '', '', 'Cpu', 3, 1, 1, 'BOTH', 0, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 10003 OR (parent_id = 0 AND menu_name = '生产与质量'));

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status, client_type, deleted, create_time, update_time)
SELECT 10004, 0, '经营支持', 'C', '', '', '', 'Tools', 4, 1, 1, 'BOTH', 0, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 10004 OR (parent_id = 0 AND menu_name = '经营支持'));

-- -----------------------------------------------------------------------------
-- 2) 购销存管理 (/erp) — Hub 页入口, 作为供应链下的容器
--    perms erp:hub:view 仅用于菜单/侧边栏授权 (后端无对应接口, 不会造成越权);
--    授权在第 8 步统一回填.
-- -----------------------------------------------------------------------------
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status, client_type, deleted, create_time, update_time)
SELECT 10011, 10002, '购销存管理', 'M', '/erp', 'erp/Hub.vue', 'erp:hub:view', 'Document', 1, 1, 1, 'BOTH', 0, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 10011 OR path = '/erp');

-- -----------------------------------------------------------------------------
-- 3) 旧 9 个顶层目录就地改造: 改名 + 类型改 C + 挂到分组
--    id=1 工作台 保留为「经营驾驶舱」页面 (menu_type=M, 无 perms, 不可授权, 原样保留)
-- -----------------------------------------------------------------------------
UPDATE sys_menu SET parent_id = 10001, menu_name = '经营驾驶舱', menu_type = 'M', is_visible = 1, status = 1, sort_no = 1
 WHERE id = 1;

UPDATE sys_menu SET parent_id = 10011, menu_name = '基础资料', menu_type = 'C', path = '', component = '', is_visible = 1, status = 1, sort_no = 1 WHERE id = 3;
UPDATE sys_menu SET parent_id = 10011, menu_name = '采购管理', menu_type = 'C', path = '', component = '', is_visible = 1, status = 1, sort_no = 2 WHERE id = 4;
UPDATE sys_menu SET parent_id = 10011, menu_name = '销售管理', menu_type = 'C', path = '', component = '', is_visible = 1, status = 1, sort_no = 3 WHERE id = 5;
UPDATE sys_menu SET parent_id = 10011, menu_name = '库存管理', menu_type = 'C', path = '', component = '', is_visible = 1, status = 1, sort_no = 4 WHERE id = 6;
UPDATE sys_menu SET parent_id = 10003, menu_name = '生产管理', menu_type = 'C', path = '', component = '', is_visible = 1, status = 1, sort_no = 1 WHERE id = 7;
UPDATE sys_menu SET parent_id = 10011, menu_name = '财务',     menu_type = 'C', path = '', component = '', is_visible = 1, status = 1, sort_no = 5 WHERE id = 8;
UPDATE sys_menu SET parent_id = 10011, menu_name = '报表中心', menu_type = 'C', path = '', component = '', is_visible = 1, status = 1, sort_no = 6 WHERE id = 9;

-- 系统管理 = 购销存管理下的第 7 个分类 (与 Hub 卡片一致)
UPDATE sys_menu SET parent_id = 10011, menu_name = '系统管理', menu_type = 'C', path = '', component = '', is_visible = 1, status = 1, sort_no = 7 WHERE id = 2;

-- -----------------------------------------------------------------------------
-- 4) 既有页面行挂到对应容器 (id 不变 → 授权不变)
-- -----------------------------------------------------------------------------
-- 基础资料
UPDATE sys_menu SET parent_id = 3, sort_no = 1, is_visible = 1, status = 1 WHERE id = 301;   -- 商品管理
UPDATE sys_menu SET parent_id = 3, sort_no = 2, is_visible = 1, status = 1 WHERE id = 302;   -- 客户管理
UPDATE sys_menu SET parent_id = 3, sort_no = 3, is_visible = 1, status = 1 WHERE id = 303;   -- 供应商管理
UPDATE sys_menu SET parent_id = 3, sort_no = 4, is_visible = 1, status = 1 WHERE id = 304;   -- 仓库管理
UPDATE sys_menu SET parent_id = 3, sort_no = 5, is_visible = 1, status = 1 WHERE id = 305;   -- 计量单位
-- 采购管理
UPDATE sys_menu SET parent_id = 4, sort_no = 1, is_visible = 1, status = 1 WHERE id = 401;   -- 采购订单 (原隐藏)
UPDATE sys_menu SET parent_id = 4, sort_no = 2, is_visible = 1, status = 1 WHERE id = 402;   -- 采购入库
UPDATE sys_menu SET parent_id = 4, sort_no = 3, is_visible = 1, status = 1 WHERE id = 403;   -- 采购退货
-- 销售管理
UPDATE sys_menu SET parent_id = 5, sort_no = 1, is_visible = 1, status = 1 WHERE id = 501;   -- 销售订单 (原隐藏)
UPDATE sys_menu SET parent_id = 5, sort_no = 2, is_visible = 1, status = 1 WHERE id = 502;   -- 销售出库
UPDATE sys_menu SET parent_id = 5, sort_no = 3, is_visible = 1, status = 1 WHERE id = 503;   -- 销售退货
-- 库存管理
UPDATE sys_menu SET parent_id = 6, sort_no = 1, is_visible = 1, status = 1 WHERE id = 601;   -- 库存查询
UPDATE sys_menu SET parent_id = 6, sort_no = 2, is_visible = 1, status = 1 WHERE id = 602;   -- 库存台账
UPDATE sys_menu SET parent_id = 6, sort_no = 3, is_visible = 1, status = 1 WHERE id = 603;   -- 库存盘点
-- 财务
UPDATE sys_menu SET parent_id = 8, sort_no = 1, is_visible = 1, status = 1 WHERE id = 801;   -- 应收应付
UPDATE sys_menu SET parent_id = 8, sort_no = 2, is_visible = 1, status = 1 WHERE id = 6036;  -- 发票管理
-- 已开发票 (无路由/无 perms 的历史残行) → 收起为停用, 不再出现在默认列表
UPDATE sys_menu SET parent_id = 8, sort_no = 9, is_visible = 0, status = 0 WHERE id = 2090345792472715265;
-- 报表中心
UPDATE sys_menu SET parent_id = 9, sort_no = 1, is_visible = 1, status = 1 WHERE id = 901;   -- 销售报表
UPDATE sys_menu SET parent_id = 9, sort_no = 2, is_visible = 1, status = 1 WHERE id = 902;   -- 库存报表
UPDATE sys_menu SET parent_id = 9, sort_no = 5, is_visible = 1, status = 1 WHERE id = 2090345792472715352; -- 回收站
UPDATE sys_menu SET parent_id = 9, sort_no = 6, is_visible = 1, status = 1 WHERE id = 2090345792472715353; -- AI 助手
UPDATE sys_menu SET parent_id = 9, sort_no = 99, is_visible = 0, status = 1 WHERE id = 951;  -- 报表查看 (按钮)
-- 系统管理
UPDATE sys_menu SET parent_id = 2, sort_no = 1 WHERE id = 201;  -- 用户管理
UPDATE sys_menu SET parent_id = 2, sort_no = 2 WHERE id = 202;  -- 角色管理
UPDATE sys_menu SET parent_id = 2, sort_no = 3 WHERE id = 203;  -- 菜单管理
UPDATE sys_menu SET parent_id = 2, sort_no = 4 WHERE id = 204;  -- 部门管理
UPDATE sys_menu SET parent_id = 2, sort_no = 5 WHERE id = 205;  -- 系统设置
UPDATE sys_menu SET parent_id = 2, sort_no = 6 WHERE id = 605;  -- 打印模板
UPDATE sys_menu SET parent_id = 2, sort_no = 7, is_visible = 0, status = 0 WHERE id = 952;  -- 飞鹅打印机 (停用)
-- 生产管理
UPDATE sys_menu SET parent_id = 7, sort_no = 1, is_visible = 1, status = 1 WHERE id = 701;   -- BOM清单
UPDATE sys_menu SET parent_id = 7, sort_no = 2, is_visible = 1, status = 1 WHERE id = 702;   -- 生产加工单
UPDATE sys_menu SET parent_id = 7, sort_no = 4, menu_name = '成品入库', is_visible = 1, status = 1 WHERE id = 3213; -- 成品入库查询 → 成品入库
UPDATE sys_menu SET parent_id = 7, sort_no = 5, is_visible = 1, status = 1 WHERE id = 2090345792472715408; -- 半成品入库
UPDATE sys_menu SET parent_id = 7, sort_no = 7, is_visible = 1, status = 1,
       component = 'production/Weight.vue' WHERE id = 2090345792472715447; -- 吹膜米重采集
UPDATE sys_menu SET parent_id = 7, sort_no = 5, component = 'production/SemiFinishedIn.vue' WHERE id = 2090345792472715408;

-- -----------------------------------------------------------------------------
-- 5) 新增页面行 (router 里存在但 sys_menu 缺失的页面)
--    perms 取 router meta.perm, 保证与前端路由守卫一致
-- -----------------------------------------------------------------------------
-- 供应链 / 基础资料
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status, client_type, deleted, create_time, update_time)
SELECT 10033, 3, '合格供应商', 'M', '/xk/material', 'xk/Material.vue', 'purchase:receipt:list', 'CircleCheck', 6, 1, 1, 'BOTH', 0, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 10033 OR path = '/xk/material');

-- 供应链 / 库存管理
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status, client_type, deleted, create_time, update_time)
SELECT 10034, 6, '库存与追溯', 'M', '/xk/trace', 'xk/Trace.vue', 'production:report:list', 'Promotion', 4, 1, 1, 'BOTH', 0, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 10034 OR path = '/xk/trace');

-- 供应链 / 采购管理
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status, client_type, deleted, create_time, update_time)
SELECT 10040, 4, '采购建议', 'M', '/purchase/suggest', 'dashboard/PurchaseSuggest.vue', 'purchase:order:add', 'ShoppingCart', 4, 1, 1, 'BOTH', 0, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 10040 OR path = '/purchase/suggest');

-- 供应链 / 报表中心
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status, client_type, deleted, create_time, update_time)
SELECT 10041, 9, '质量报表', 'M', '/report/quality', 'report/Quality.vue', 'quality:inspection:list', 'PieChart', 3, 1, 1, 'BOTH', 0, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 10041 OR path = '/report/quality');

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status, client_type, deleted, create_time, update_time)
SELECT 10043, 9, '设备报表', 'M', '/report/equipment', 'report/Equipment.vue', 'equipment:maintain:list', 'DataAnalysis', 4, 1, 1, 'BOTH', 0, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 10043 OR path = '/report/equipment');

-- 生产与质量 / 生产管理
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status, client_type, deleted, create_time, update_time)
SELECT 10031, 7, '领料记录', 'M', '/production/requisition', 'production/Requisition.vue', 'production:requisition:list', 'Notebook', 3, 1, 1, 'BOTH', 0, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 10031 OR path = '/production/requisition');

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status, client_type, deleted, create_time, update_time)
SELECT 10032, 7, '生产排程', 'M', '/production/schedule', 'production/Schedule.vue', 'production:schedule:list', 'DataLine', 6, 1, 1, 'BOTH', 0, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 10032 OR path = '/production/schedule');

-- 生产与质量 / 设备维保
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status, client_type, deleted, create_time, update_time)
SELECT 10035, 10003, '设备维保', 'M', '/xk/maintenance', 'xk/Maintenance.vue', 'work:maintain:list', 'Tools', 2, 1, 1, 'BOTH', 0, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 10035 OR path = '/xk/maintenance');

-- 生产与质量 / 质量与XK检查
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status, client_type, deleted, create_time, update_time)
SELECT 10036, 10003, '质量与XK检查', 'M', '/xk/safety', 'xk/Safety.vue', 'work:safety:list', 'CircleCheck', 3, 1, 1, 'BOTH', 0, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 10036 OR path = '/xk/safety');

-- 生产与质量 / 进货查验台账
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status, client_type, deleted, create_time, update_time)
SELECT 10037, 10003, '进货查验台账', 'M', '/xk/iqc-ledger', 'xk/IqcLedger.vue', 'qms:iqc:list', 'Document', 4, 1, 1, 'BOTH', 0, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 10037 OR path = '/xk/iqc-ledger');

-- 生产与质量 / 检验台账
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status, client_type, deleted, create_time, update_time)
SELECT 10042, 10003, '检验台账', 'M', '/report/inspection', 'quality/Inspection.vue', 'quality:inspection:list', 'List', 5, 1, 1, 'BOTH', 0, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 10042 OR path = '/report/inspection');

-- 经营支持
INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status, client_type, deleted, create_time, update_time)
SELECT 10038, 10004, '单据与打印', 'M', '/xk/print', 'xk/Print.vue', 'system:print:list', 'Printer', 1, 1, 1, 'BOTH', 0, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 10038 OR path = '/xk/print');

INSERT INTO sys_menu (id, parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status, client_type, deleted, create_time, update_time)
SELECT 10039, 10004, '审计归档', 'M', '/xk/audit', 'xk/Audit.vue', 'report:view', 'Clock', 2, 1, 1, 'BOTH', 0, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id = 10039 OR path = '/xk/audit');

-- -----------------------------------------------------------------------------
-- 6) 按钮 / 功能权限行挂到所属页面 (id 不变, 只改 parent_id / sort_no)
--    目的: 角色授权树里按钮跟在页面下面, 不再散落成 100+ 个根节点
--    (无 UI 载体的权限点保持 parent_id=0 不动)
-- -----------------------------------------------------------------------------
-- 经营驾驶舱
UPDATE sys_menu SET parent_id = 1, sort_no = 1 WHERE id = 2090345792472715346; -- 销售指标
UPDATE sys_menu SET parent_id = 1, sort_no = 2 WHERE id = 2090345792472715347; -- 销售趋势
UPDATE sys_menu SET parent_id = 1, sort_no = 3 WHERE id = 2090345792472715348; -- 销售排行
UPDATE sys_menu SET parent_id = 1, sort_no = 4 WHERE id = 2090345792472715300; -- 库存预警

-- 基础资料
UPDATE sys_menu SET parent_id = 301, sort_no = 1 WHERE id = 6034;  -- 新增商品
UPDATE sys_menu SET parent_id = 301, sort_no = 2 WHERE id = 2090345792472715307; -- 商品编辑
UPDATE sys_menu SET parent_id = 301, sort_no = 3 WHERE id = 2090345792472715308; -- 商品删除
UPDATE sys_menu SET parent_id = 302, sort_no = 1 WHERE id = 2090345792472715309; -- 客户新增
UPDATE sys_menu SET parent_id = 302, sort_no = 2 WHERE id = 2090345792472715310; -- 客户编辑
UPDATE sys_menu SET parent_id = 302, sort_no = 3 WHERE id = 2090345792472715311; -- 客户删除
UPDATE sys_menu SET parent_id = 303, sort_no = 1 WHERE id = 2090345792472715312; -- 供应商新增
UPDATE sys_menu SET parent_id = 303, sort_no = 2 WHERE id = 2090345792472715313; -- 供应商编辑
UPDATE sys_menu SET parent_id = 303, sort_no = 3 WHERE id = 2090345792472715314; -- 供应商删除
UPDATE sys_menu SET parent_id = 304, sort_no = 1 WHERE id = 2090345792472715315; -- 仓库新增
UPDATE sys_menu SET parent_id = 304, sort_no = 2 WHERE id = 2090345792472715316; -- 仓库编辑
UPDATE sys_menu SET parent_id = 304, sort_no = 3 WHERE id = 2090345792472715317; -- 仓库删除
UPDATE sys_menu SET parent_id = 305, sort_no = 1 WHERE id = 2090345792472715318; -- 单位新增
UPDATE sys_menu SET parent_id = 305, sort_no = 2 WHERE id = 2090345792472715319; -- 单位编辑
UPDATE sys_menu SET parent_id = 305, sort_no = 3 WHERE id = 2090345792472715320; -- 单位删除

-- 采购订单
UPDATE sys_menu SET parent_id = 401, sort_no = 1 WHERE id = 2090345792472715276; -- 采购订单新增
UPDATE sys_menu SET parent_id = 401, sort_no = 2 WHERE id = 2090345792472715277; -- 采购订单编辑
UPDATE sys_menu SET parent_id = 401, sort_no = 3 WHERE id = 2090345792472715278; -- 采购订单删除
UPDATE sys_menu SET parent_id = 401, sort_no = 4 WHERE id = 2090345792472715279; -- 采购订单审核
UPDATE sys_menu SET parent_id = 401, sort_no = 70 WHERE id = 6047; -- 审核采购订单
UPDATE sys_menu SET parent_id = 401, sort_no = 71 WHERE id = 6048; -- 反审核采购订单
UPDATE sys_menu SET parent_id = 401, sort_no = 72 WHERE id = 6049; -- 打印采购订单
-- 采购入库
UPDATE sys_menu SET parent_id = 402, sort_no = 1 WHERE id = 2090345792472715280; -- 采购入库新增
UPDATE sys_menu SET parent_id = 402, sort_no = 2 WHERE id = 2090345792472715281; -- 采购入库编辑
UPDATE sys_menu SET parent_id = 402, sort_no = 3 WHERE id = 2090345792472715282; -- 采购入库删除
UPDATE sys_menu SET parent_id = 402, sort_no = 4 WHERE id = 2090345792472715283; -- 采购入库审核
UPDATE sys_menu SET parent_id = 402, sort_no = 5 WHERE id = 2090345792472715349; -- 采购入库反审核
UPDATE sys_menu SET parent_id = 402, sort_no = 6 WHERE id = 2090345792472715351; -- 采购入库单查询
UPDATE sys_menu SET parent_id = 402, sort_no = 70 WHERE id = 6052; -- 审核入库
UPDATE sys_menu SET parent_id = 402, sort_no = 72 WHERE id = 6051; -- 打印
-- 采购退货
UPDATE sys_menu SET parent_id = 403, sort_no = 1 WHERE id = 2090345792472715284; -- 采购退货新增
UPDATE sys_menu SET parent_id = 403, sort_no = 2 WHERE id = 2090345792472715285; -- 采购退货审核
UPDATE sys_menu SET parent_id = 403, sort_no = 70 WHERE id = 6053; -- 审核采购退货
UPDATE sys_menu SET parent_id = 403, sort_no = 71 WHERE id = 6054; -- 反审核采购退货
UPDATE sys_menu SET parent_id = 403, sort_no = 72 WHERE id = 6055; -- 打印采购退货

-- 销售订单
UPDATE sys_menu SET parent_id = 501, sort_no = 1 WHERE id = 2090345792472715266; -- 销售订单新增
UPDATE sys_menu SET parent_id = 501, sort_no = 2 WHERE id = 2090345792472715267; -- 销售订单编辑
UPDATE sys_menu SET parent_id = 501, sort_no = 3 WHERE id = 2090345792472715268; -- 销售订单删除
UPDATE sys_menu SET parent_id = 501, sort_no = 4 WHERE id = 2090345792472715269; -- 销售订单审核
UPDATE sys_menu SET parent_id = 501, sort_no = 70 WHERE id = 6056; -- 审核销售订单
UPDATE sys_menu SET parent_id = 501, sort_no = 71 WHERE id = 6057; -- 反审核销售订单
UPDATE sys_menu SET parent_id = 501, sort_no = 72 WHERE id = 6058; -- 打印销售订单
-- 销售出库
UPDATE sys_menu SET parent_id = 502, sort_no = 1 WHERE id = 2090345792472715270; -- 销售出库新增
UPDATE sys_menu SET parent_id = 502, sort_no = 2 WHERE id = 2090345792472715271; -- 销售出库编辑
UPDATE sys_menu SET parent_id = 502, sort_no = 3 WHERE id = 2090345792472715272; -- 销售出库删除
UPDATE sys_menu SET parent_id = 502, sort_no = 4 WHERE id = 2090345792472715273; -- 销售出库审核
UPDATE sys_menu SET parent_id = 502, sort_no = 5 WHERE id = 2090345792472715350; -- 销售出库反审核
UPDATE sys_menu SET parent_id = 502, sort_no = 50 WHERE id = 6041; -- 审核出库
UPDATE sys_menu SET parent_id = 502, sort_no = 52 WHERE id = 6043; -- 打印
-- 销售退货
UPDATE sys_menu SET parent_id = 503, sort_no = 1 WHERE id = 2090345792472715274; -- 销售退货新增
UPDATE sys_menu SET parent_id = 503, sort_no = 2 WHERE id = 2090345792472715275; -- 销售退货审核
UPDATE sys_menu SET parent_id = 503, sort_no = 70 WHERE id = 6061; -- 审核销售退货
UPDATE sys_menu SET parent_id = 503, sort_no = 71 WHERE id = 6059; -- 反审核
UPDATE sys_menu SET parent_id = 503, sort_no = 72 WHERE id = 6060; -- 打印

-- 库存
UPDATE sys_menu SET parent_id = 601, sort_no = 1 WHERE id = 2090345792472715298; -- 库存调拨新增
UPDATE sys_menu SET parent_id = 601, sort_no = 2 WHERE id = 2090345792472715299; -- 库存调拨审核
UPDATE sys_menu SET parent_id = 603, sort_no = 1 WHERE id = 6031; -- 新增盘点单
UPDATE sys_menu SET parent_id = 603, sort_no = 2 WHERE id = 6032; -- 审核盘点单
UPDATE sys_menu SET parent_id = 603, sort_no = 3 WHERE id = 6033; -- 删除盘点单
UPDATE sys_menu SET parent_id = 603, sort_no = 71 WHERE id = 6062; -- 反审核盘点单
UPDATE sys_menu SET parent_id = 603, sort_no = 72 WHERE id = 6063; -- 打印盘点单

-- 生产
UPDATE sys_menu SET parent_id = 701, sort_no = 1 WHERE id = 2090345792472715286; -- BOM新增
UPDATE sys_menu SET parent_id = 701, sort_no = 2 WHERE id = 2090345792472715287; -- BOM编辑
UPDATE sys_menu SET parent_id = 701, sort_no = 3 WHERE id = 2090345792472715288; -- BOM删除
UPDATE sys_menu SET parent_id = 702, sort_no = 1 WHERE id = 2090345792472715289; -- 生产订单编辑
UPDATE sys_menu SET parent_id = 702, sort_no = 2 WHERE id = 2090345792472715290; -- 生产订单删除
UPDATE sys_menu SET parent_id = 702, sort_no = 3 WHERE id = 2090345792472715291; -- 生产订单下达
UPDATE sys_menu SET parent_id = 702, sort_no = 4 WHERE id = 2090345792472715292; -- 生产订单完工
UPDATE sys_menu SET parent_id = 702, sort_no = 5 WHERE id = 2090345792472715377; -- MES报工
UPDATE sys_menu SET parent_id = 702, sort_no = 6 WHERE id = 2090345792472715378; -- MES报工录入
UPDATE sys_menu SET parent_id = 702, sort_no = 99 WHERE id = 6035; -- 新增生产加工单
UPDATE sys_menu SET parent_id = 702, sort_no = 98 WHERE id = 959;  -- 飞鹅打印 (停用)
-- 领料记录
UPDATE sys_menu SET parent_id = 10031, sort_no = 1, path = '' WHERE id = 2090345792472715407; -- 领料单查询 (原 path='#')
UPDATE sys_menu SET parent_id = 10031, sort_no = 2 WHERE id = 2090345792472715293; -- 领料单新增
UPDATE sys_menu SET parent_id = 10031, sort_no = 3 WHERE id = 2090345792472715294; -- 领料单审核
UPDATE sys_menu SET parent_id = 10031, sort_no = 4 WHERE id = 2090345792472715295; -- 领料单删除
-- 成品入库
UPDATE sys_menu SET parent_id = 3213, sort_no = 1 WHERE id = 2090345792472715296; -- 成品入库新增
UPDATE sys_menu SET parent_id = 3213, sort_no = 2 WHERE id = 2090345792472715297; -- 成品入库审核
UPDATE sys_menu SET parent_id = 3213, sort_no = 3 WHERE id = 3214; -- 成品入库删除
UPDATE sys_menu SET parent_id = 3213, sort_no = 4 WHERE id = 3215; -- 成品入库反审核
-- 半成品入库
UPDATE sys_menu SET parent_id = 2090345792472715408, sort_no = 1 WHERE id = 2090345792472715409; -- 新增
UPDATE sys_menu SET parent_id = 2090345792472715408, sort_no = 2 WHERE id = 2090345792472715410; -- 审核
UPDATE sys_menu SET parent_id = 2090345792472715408, sort_no = 3 WHERE id = 2090345792472715411; -- 删除
UPDATE sys_menu SET parent_id = 2090345792472715408, sort_no = 4 WHERE id = 2090345792472715412; -- 反审核
-- 生产排程
UPDATE sys_menu SET parent_id = 10032, sort_no = 1 WHERE id = 2090345792472715413; -- 排程查看
UPDATE sys_menu SET parent_id = 10032, sort_no = 2 WHERE id = 2090345792472715414; -- 排程维护
-- 吹膜米重采集
UPDATE sys_menu SET parent_id = 2090345792472715447, sort_no = 1 WHERE id = 2090345792472715445; -- 查看
UPDATE sys_menu SET parent_id = 2090345792472715447, sort_no = 2 WHERE id = 2090345792472715446; -- 维护

-- 财务
UPDATE sys_menu SET parent_id = 801, sort_no = 1 WHERE id = 2090345792472715305; -- 收款
UPDATE sys_menu SET parent_id = 801, sort_no = 2 WHERE id = 2090345792472715306; -- 付款
UPDATE sys_menu SET parent_id = 6036, sort_no = 1 WHERE id = 6037; -- 申请开票
UPDATE sys_menu SET parent_id = 6036, sort_no = 2 WHERE id = 6038; -- 作废发票
UPDATE sys_menu SET parent_id = 6036, sort_no = 3 WHERE id = 6039; -- 按发票收款
UPDATE sys_menu SET parent_id = 6036, sort_no = 4 WHERE id = 6040; -- 未开票列表

-- 系统管理
UPDATE sys_menu SET parent_id = 201, sort_no = 1 WHERE id = 2090345792472715321; -- 用户新增
UPDATE sys_menu SET parent_id = 201, sort_no = 2 WHERE id = 2090345792472715322; -- 用户编辑
UPDATE sys_menu SET parent_id = 201, sort_no = 3 WHERE id = 2090345792472715323; -- 用户删除
UPDATE sys_menu SET parent_id = 201, sort_no = 4 WHERE id = 2090345792472715324; -- 分配角色
UPDATE sys_menu SET parent_id = 201, sort_no = 5 WHERE id = 2090345792472715325; -- 重置密码
UPDATE sys_menu SET parent_id = 202, sort_no = 1 WHERE id = 2090345792472715326; -- 角色新增
UPDATE sys_menu SET parent_id = 202, sort_no = 2 WHERE id = 2090345792472715327; -- 角色编辑
UPDATE sys_menu SET parent_id = 202, sort_no = 3 WHERE id = 2090345792472715328; -- 角色删除
UPDATE sys_menu SET parent_id = 202, sort_no = 4 WHERE id = 2090345792472715329; -- 分配用户
UPDATE sys_menu SET parent_id = 202, sort_no = 5 WHERE id = 2090345792472715330; -- 授权菜单
UPDATE sys_menu SET parent_id = 203, sort_no = 1 WHERE id = 2090345792472715331; -- 菜单新增
UPDATE sys_menu SET parent_id = 203, sort_no = 2 WHERE id = 2090345792472715332; -- 菜单编辑
UPDATE sys_menu SET parent_id = 203, sort_no = 3 WHERE id = 2090345792472715333; -- 菜单删除
UPDATE sys_menu SET parent_id = 204, sort_no = 1 WHERE id = 2090345792472715334; -- 部门新增
UPDATE sys_menu SET parent_id = 204, sort_no = 2 WHERE id = 2090345792472715335; -- 部门编辑
UPDATE sys_menu SET parent_id = 204, sort_no = 3 WHERE id = 2090345792472715336; -- 部门删除
UPDATE sys_menu SET parent_id = 205, sort_no = 1 WHERE id = 2090345792472715337; -- 系统配置新增
UPDATE sys_menu SET parent_id = 205, sort_no = 2 WHERE id = 2090345792472715338; -- 系统配置编辑
UPDATE sys_menu SET parent_id = 205, sort_no = 3 WHERE id = 2090345792472715339; -- 系统配置删除
UPDATE sys_menu SET parent_id = 205, sort_no = 4 WHERE id = 2090345792472715343; -- 操作日志查看
UPDATE sys_menu SET parent_id = 205, sort_no = 5 WHERE id = 2090345792472715344; -- 操作日志清理
UPDATE sys_menu SET parent_id = 205, sort_no = 6 WHERE id = 2090345792472715345; -- 登录日志查看
UPDATE sys_menu SET parent_id = 605, sort_no = 1 WHERE id = 2090345792472715340; -- 打印模板新增
UPDATE sys_menu SET parent_id = 605, sort_no = 2 WHERE id = 2090345792472715341; -- 打印模板编辑
UPDATE sys_menu SET parent_id = 605, sort_no = 3 WHERE id = 2090345792472715342; -- 打印模板删除

-- 设备维保 (老 /workflow 死子树的按钮行就地归位到现页面; id 不变 → 授权不变)
UPDATE sys_menu SET parent_id = 10035, sort_no = 1 WHERE id = 2090345792472715362; -- 维保记录新增
UPDATE sys_menu SET parent_id = 10035, sort_no = 2 WHERE id = 2090345792472715363; -- 维保记录编辑
UPDATE sys_menu SET parent_id = 10035, sort_no = 3 WHERE id = 2090345792472715364; -- 维保记录删除
UPDATE sys_menu SET parent_id = 10035, sort_no = 4 WHERE id = 2090345792472715365; -- 维保记录审核
UPDATE sys_menu SET parent_id = 10035, sort_no = 5 WHERE id = 2090345792472715366; -- 维保记录反审核
UPDATE sys_menu SET parent_id = 10035, sort_no = 11 WHERE id = 2090345792472715402; -- 设备维保-新增
UPDATE sys_menu SET parent_id = 10035, sort_no = 12 WHERE id = 2090345792472715403; -- 设备维保查询
UPDATE sys_menu SET parent_id = 10035, sort_no = 13 WHERE id = 2090345792472715404; -- 设备维保审核
UPDATE sys_menu SET parent_id = 10035, sort_no = 21 WHERE id = 2090345792472715415; -- 维保计划查询
UPDATE sys_menu SET parent_id = 10035, sort_no = 22 WHERE id = 2090345792472715416; -- 维保计划新增
UPDATE sys_menu SET parent_id = 10035, sort_no = 23 WHERE id = 2090345792472715417; -- 维保计划编辑
UPDATE sys_menu SET parent_id = 10035, sort_no = 24 WHERE id = 2090345792472715418; -- 维保计划删除
UPDATE sys_menu SET parent_id = 10035, sort_no = 31 WHERE id = 2090345792472715419; -- 维保工单查询
UPDATE sys_menu SET parent_id = 10035, sort_no = 32 WHERE id = 2090345792472715420; -- 维保工单新增
UPDATE sys_menu SET parent_id = 10035, sort_no = 33 WHERE id = 2090345792472715421; -- 维保工单编辑
UPDATE sys_menu SET parent_id = 10035, sort_no = 34 WHERE id = 2090345792472715422; -- 维保工单完成
UPDATE sys_menu SET parent_id = 10035, sort_no = 41 WHERE id = 2090345792472715423; -- 维保记录查询
UPDATE sys_menu SET parent_id = 10035, sort_no = 42 WHERE id = 2090345792472715424; -- 维保记录审核(em)
UPDATE sys_menu SET parent_id = 10035, sort_no = 43 WHERE id = 2090345792472715425; -- 维保记录导出
UPDATE sys_menu SET parent_id = 10035, sort_no = 51 WHERE id = 2090345792472715398; -- 设备主数据-查询
UPDATE sys_menu SET parent_id = 10035, sort_no = 52 WHERE id = 2090345792472715399; -- 设备主数据-新增
UPDATE sys_menu SET parent_id = 10035, sort_no = 53 WHERE id = 2090345792472715400; -- 设备主数据-编辑
UPDATE sys_menu SET parent_id = 10035, sort_no = 54 WHERE id = 2090345792472715401; -- 设备主数据-删除

-- 质量与XK检查
UPDATE sys_menu SET parent_id = 10036, sort_no = 1 WHERE id = 2090345792472715367; -- 安全检查新增
UPDATE sys_menu SET parent_id = 10036, sort_no = 2 WHERE id = 2090345792472715368; -- 安全检查编辑
UPDATE sys_menu SET parent_id = 10036, sort_no = 3 WHERE id = 2090345792472715369; -- 安全检查删除
UPDATE sys_menu SET parent_id = 10036, sort_no = 4 WHERE id = 2090345792472715370; -- 安全检查审核
UPDATE sys_menu SET parent_id = 10036, sort_no = 5 WHERE id = 2090345792472715371; -- 安全检查反审核
UPDATE sys_menu SET parent_id = 10036, sort_no = 11 WHERE id = 2090345792472715426; -- XK环境检查查询
UPDATE sys_menu SET parent_id = 10036, sort_no = 12 WHERE id = 2090345792472715427; -- 新增
UPDATE sys_menu SET parent_id = 10036, sort_no = 13 WHERE id = 2090345792472715428; -- 编辑
UPDATE sys_menu SET parent_id = 10036, sort_no = 14 WHERE id = 2090345792472715429; -- 审核
UPDATE sys_menu SET parent_id = 10036, sort_no = 15 WHERE id = 2090345792472715430; -- 导出

-- 审计归档
UPDATE sys_menu SET parent_id = 10039, sort_no = 1 WHERE id = 2090345792472715405; -- XK 审计日志查询
UPDATE sys_menu SET parent_id = 10039, sort_no = 2 WHERE id = 2090345792472715406; -- XK 记录归档查询

-- 检验台账
UPDATE sys_menu SET parent_id = 10042, sort_no = 1 WHERE id = 2090345792472715379; -- 质量检验-新增
UPDATE sys_menu SET parent_id = 10042, sort_no = 2 WHERE id = 2090345792472715380; -- 质量检验查询
UPDATE sys_menu SET parent_id = 10042, sort_no = 3 WHERE id = 2090345792472715381; -- 质量检验审核
UPDATE sys_menu SET parent_id = 10042, sort_no = 11 WHERE id = 2090345792472715382; -- IQC 查询
UPDATE sys_menu SET parent_id = 10042, sort_no = 12 WHERE id = 2090345792472715383; -- IQC 审核
UPDATE sys_menu SET parent_id = 10042, sort_no = 21 WHERE id = 2090345792472715384; -- FAI 查询
UPDATE sys_menu SET parent_id = 10042, sort_no = 22 WHERE id = 2090345792472715385; -- FAI 审核
UPDATE sys_menu SET parent_id = 10042, sort_no = 31 WHERE id = 2090345792472715386; -- PAT 查询
UPDATE sys_menu SET parent_id = 10042, sort_no = 32 WHERE id = 2090345792472715387; -- PAT 审核
UPDATE sys_menu SET parent_id = 10042, sort_no = 41 WHERE id = 2090345792472715388; -- OQC 查询
UPDATE sys_menu SET parent_id = 10042, sort_no = 42 WHERE id = 2090345792472715389; -- OQC 审核

-- 进货查验台账
UPDATE sys_menu SET parent_id = 10037, sort_no = 1 WHERE id = 2090345792472715442; -- 台账
UPDATE sys_menu SET parent_id = 10037, sort_no = 2 WHERE id = 2090345792472715443; -- 详情
UPDATE sys_menu SET parent_id = 10037, sort_no = 3 WHERE id = 2090345792472715444; -- 报告

-- 6.5) 按钮/功能行统一 is_visible=0: 按钮不出现在导航里, 统一收进「显示停用/隐藏项」
--      (老数据里 6047/6052/6062 等 B 行是 is_visible=1, 与 F 行不一致)
UPDATE sys_menu SET is_visible = 0 WHERE deleted = 0 AND menu_type IN ('B', 'F') AND is_visible = 1;

-- -----------------------------------------------------------------------------
-- 7) 新增页面行的授权回填
--    规则: 角色已拥有该页面 perms → 补一条该页面的 menu 授权 (client_type 跟随来源行),
--          保证非管理员侧边栏 (MainLayout allowedPaths 按 path 过滤) 能看到对应入口.
--    幂等: INSERT IGNORE (主键 role_id+menu_id+client_type)
-- -----------------------------------------------------------------------------
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT DISTINCT rm.role_id, 10031, rm.client_type
  FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id
 WHERE m.perms = 'production:requisition:list';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT DISTINCT rm.role_id, 10032, rm.client_type
  FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id
 WHERE m.perms = 'production:schedule:list';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT DISTINCT rm.role_id, 10033, rm.client_type
  FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id
 WHERE m.perms = 'purchase:receipt:list';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT DISTINCT rm.role_id, 10034, rm.client_type
  FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id
 WHERE m.perms = 'production:report:list';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT DISTINCT rm.role_id, 10035, rm.client_type
  FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id
 WHERE m.perms = 'work:maintain:list';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT DISTINCT rm.role_id, 10036, rm.client_type
  FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id
 WHERE m.perms = 'work:safety:list';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT DISTINCT rm.role_id, 10037, rm.client_type
  FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id
 WHERE m.perms = 'qms:iqc:list';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT DISTINCT rm.role_id, 10038, rm.client_type
  FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id
 WHERE m.perms = 'system:print:list';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT DISTINCT rm.role_id, 10039, rm.client_type
  FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id
 WHERE m.perms = 'report:view';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT DISTINCT rm.role_id, 10040, rm.client_type
  FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id
 WHERE m.perms = 'purchase:order:add';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT DISTINCT rm.role_id, 10041, rm.client_type
  FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id
 WHERE m.perms = 'quality:inspection:list';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT DISTINCT rm.role_id, 10042, rm.client_type
  FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id
 WHERE m.perms = 'quality:inspection:list';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT DISTINCT rm.role_id, 10043, rm.client_type
  FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id
 WHERE m.perms = 'equipment:maintain:list';

-- 购销存管理 (/erp) 无既有 perms: 授予"有任一 ERP 业务页面授权"的角色 (BOTH)
INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
SELECT DISTINCT r.id, 10011, 'BOTH'
  FROM sys_role r
 WHERE r.deleted = 0
   AND EXISTS (
     SELECT 1 FROM sys_role_menu rm
       JOIN sys_menu m ON m.id = rm.menu_id
      WHERE rm.role_id = r.id
        AND m.parent_id IN (3, 4, 5, 6, 7, 8, 9)
   );

-- -----------------------------------------------------------------------------
-- 8) 校验 (只读, 执行后逐条确认)
-- -----------------------------------------------------------------------------
-- 8.1 顶层应恰好 4 组 + 未归属权限点 (menu_type='F', is_visible=0)
SELECT id, menu_name, menu_type, path, status, is_visible
  FROM sys_menu
 WHERE deleted = 0 AND parent_id = 0
 ORDER BY sort_no, id;

-- 8.2 新结构树 (按 sort_no 排序)
WITH RECURSIVE t AS (
  SELECT id, parent_id, menu_name, menu_type, path, perms, sort_no, is_visible, status, 1 AS lvl,
         CAST(CONCAT(LPAD(sort_no, 6, '0'), '-', LPAD(id, 20, '0')) AS CHAR(400)) AS sortpath
    FROM sys_menu WHERE deleted = 0 AND parent_id = 0 AND status = 1 AND menu_type = 'C'
  UNION ALL
  SELECT m.id, m.parent_id, m.menu_name, m.menu_type, m.path, m.perms, m.sort_no, m.is_visible, m.status, t.lvl + 1,
         CONCAT(t.sortpath, '-', LPAD(m.sort_no, 6, '0'), '-', LPAD(m.id, 20, '0'))
    FROM sys_menu m JOIN t ON m.parent_id = t.id
   WHERE m.deleted = 0 AND m.status = 1
)
SELECT CONCAT(REPEAT('    ', lvl - 1), menu_name, IFNULL(CONCAT('  ', path), ''), IF(perms IS NOT NULL AND perms <> '', CONCAT('  [', perms, ']'), '')) AS tree
  FROM t ORDER BY sortpath;

-- 8.3 授权完整性: 指向不存在菜单的授权应为 0 行
-- SELECT rm.role_id, rm.menu_id FROM sys_role_menu rm
--   LEFT JOIN sys_menu m ON m.id = rm.menu_id AND m.deleted = 0 WHERE m.id IS NULL;
-- 8.4 与执行前对比: 授权行数只应增加
-- SELECT (SELECT COUNT(*) FROM sys_role_menu_bak_v1130) AS before_cnt, (SELECT COUNT(*) FROM sys_role_menu) AS after_cnt;

-- =============================================================================
-- 回滚 (手工执行, 按需):
--   DELETE FROM sys_role_menu WHERE menu_id BETWEEN 10001 AND 10099;
--   DELETE FROM sys_menu WHERE id BETWEEN 10001 AND 10099;
--   UPDATE sys_menu m JOIN sys_menu_bak_v1130 b ON b.id = m.id
--      SET m.parent_id=b.parent_id, m.menu_name=b.menu_name, m.menu_type=b.menu_type,
--          m.path=b.path, m.component=b.component, m.perms=b.perms, m.icon=b.icon,
--          m.sort_no=b.sort_no, m.is_visible=b.is_visible, m.status=b.status, m.client_type=b.client_type;
--   (sys_role_menu 原有行未被修改, 无需恢复; 如需完全还原: TRUNCATE sys_role_menu;
--    INSERT INTO sys_role_menu SELECT * FROM sys_role_menu_bak_v1130;)
-- =============================================================================
