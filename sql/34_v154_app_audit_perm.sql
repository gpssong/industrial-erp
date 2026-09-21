-- =====================================================================
-- 三十四、v1.1.54 — App 端采购入库/销售出库审核 perm 授权补齐 (hotfix)
-- =====================================================================
--
-- 背景:
--   2026-09-21 用户反馈: App 端采购入库单/销售出库单需要加审核功能.
--   PC 端审核 end-to-end 已存在 (PurReceiptController.java:67-79 +
--   SalDeliveryController.java:79-91 + pc-web Receipt.vue/Delivery.vue),
--   App 端详情页加审核按钮 (v1.1.54 frontend) 即可调通.
--
-- 根因:
--   - 后端 perm `purchase:receipt:check` (sql/28_v124_permissions.sql:46) +
--     `sales:delivery:check` (sql/28:33) 已 seed, F 类型按钮权限
--   - sql/28 seed 只给 6 个内置角色 (SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/
--     WAREHOUSE_MGR/PRODUCTION_MGR/FINANCE) 授权 PC 端 check, **未覆盖
--     v1.1.27 之后新建的非标角色** (制袋工 zdg / 吹膜工 cmg 等)
--   - App 端 check perm 完全没绑 — 任何非内置角色都无法在 App 端审核
--
-- 修复:
--   凡是有 `xxx:list` APP 授权的角色, 自动补一份 `xxx:check` APP 授权.
--   (同 mode 与 sql/33_v153_scan_in_perm.sql 保持一致, 都用"关联查询触发"
--    而非"对已知角色 INSERT", 涵盖 v1.1.27 之后的所有新角色)
--
-- 设计考虑:
--   - 用 INSERT IGNORE 兼容历史 hotfix 数据 (idempotent)
--   - WHERE 条件限定 client_type='APP' 触发, 不动 PC 端已有授权
--   - 用 JOIN sys_menu 查 perm 对应 menu_id, 不 hardcode 兼容 sql/28 后续调整
--   - 不删任何已有 PC/APP 记录 (只新增缺失的 APP check 行)
--
-- ⚠️ MySQL 8 容器默认 latin1, 必须:
--   docker exec -i erp-mysql mysql industrial_erp -uroot -p$PW \
--     --default-character-set=utf8mb4 < 34_v154_app_audit_perm.sql
-- 否则含中文的校验查询结果会 mojibake (此脚本 INSERT 部分无中文, 但校验 SELECT 有)
-- =====================================================================

USE `industrial_erp`;

-- 1. 采购入库审核: 凡是有 purchase:receipt:list APP 授权的角色, 补 purchase:receipt:check APP 授权
INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`,`client_type`)
SELECT DISTINCT rm.role_id, m.id, 'APP'
FROM `sys_role_menu` rm
JOIN `sys_menu` m ON m.perms = 'purchase:receipt:check' AND m.deleted = 0
WHERE rm.menu_id IN (SELECT id FROM `sys_menu` WHERE perms = 'purchase:receipt:list' AND deleted = 0)
  AND rm.client_type = 'APP';

-- 2. 销售出库审核: 凡是有 sales:delivery:list APP 授权的角色, 补 sales:delivery:check APP 授权
INSERT IGNORE INTO `sys_role_menu`(`role_id`,`menu_id`,`client_type`)
SELECT DISTINCT rm.role_id, m.id, 'APP'
FROM `sys_role_menu` rm
JOIN `sys_menu` m ON m.perms = 'sales:delivery:check' AND m.deleted = 0
WHERE rm.menu_id IN (SELECT id FROM `sys_menu` WHERE perms = 'sales:delivery:list' AND deleted = 0)
  AND rm.client_type = 'APP';

-- 3. 校验: 凡是有 list APP 授权的角色, 现在都应该同时有 check APP 授权
SELECT r.role_code, r.role_name,
       EXISTS(SELECT 1 FROM `sys_role_menu` rm
              JOIN `sys_menu` m ON m.id = rm.menu_id
              WHERE rm.role_id = r.id AND m.perms = 'purchase:receipt:check'
                AND rm.client_type = 'APP') AS has_pur_check_app,
       EXISTS(SELECT 1 FROM `sys_role_menu` rm
              JOIN `sys_menu` m ON m.id = rm.menu_id
              WHERE rm.role_id = r.id AND m.perms = 'sales:delivery:check'
                AND rm.client_type = 'APP') AS has_sal_check_app
FROM `sys_role` r
WHERE r.deleted = 0
ORDER BY r.id;

-- 预期:
--   - 6 个内置角色 (SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/WAREHOUSE_MGR/
--     PRODUCTION_MGR/FINANCE) has_pur_check_app=1 AND has_sal_check_app=1
--   - 制袋工(zdg) 凑齐两个 (sql/33 已补 list APP, 本脚本补 check APP)
--   - 吹膜工(cmg) 同上
--   - 没 list APP 授权的角色 has_*_check_app=0 (本脚本不会自动绑)

-- 4. 用户操作: 让受影响的 App 用户**退出 App 重新登录**一次
--    Sa-Token session 缓存的权限会从 DB 重新加载, 新授权立即生效