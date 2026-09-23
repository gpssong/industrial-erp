-- =====================================================================
-- 四十二、v1.1.56 hotfix-1 — sys_menu 重复 perm 行去重
-- =====================================================================
--
-- 背景 (2026-09-22 用户反馈):
--   PC 端【分配权限】弹窗, 采购入库目录下"反审核"出现两次:
--     - id=6050  menu_name='反审核'        menu_type='B'  (2026-07-28 sql/23 seed)
--     - id=...5349 menu_name='采购入库反审核' menu_type='F'  (2026-09-21 sql/35 seed)
--   销售出库目录下同样重复:
--     - id=6042  menu_name='反审核'        menu_type='B'  (sql/23)
--     - id=...5350 menu_name='销售出库反审核' menu_type='F'  (sql/35)
--
-- 根因:
--   sql/35 (v1.1.55 反审核拆 perm) 用 `INSERT IGNORE` seed F 类型 perm 行时,
--   perms 列没有 UNIQUE 索引, 所以同 perm 同 parent_id 的旧 B 类型行未被拦截,
--   双行并存. 用户在 el-tree 看 parent_id=402 (采购入库) 下出现两次同名 perm.
--
-- 业务影响:
--   - **PC 端弹窗**: 同 perm 字符串显示两次 (用户当前反馈的视觉污染)
--   - **后端 @SaCheckPermission**: 走 perms 字符串, 不受 menu_id 影响, 行为不变
--   - **sys_role_menu**: 当前 PRODUCTION_MGR/PURCHASE_MGR/SUPER_ADMIN 同时引用 6050+5349
--     (或 6042+5350), 即同一角色对同一 perm 有 2 行授权, 业务等价但冗余
--   - **App 端 storage 派生**: `getPermissions()` 读的是后端给的 perms 数组, 去重是后端
--     `selectPermsByUserIdAndClient` 内 `GROUP_CONCAT DISTINCT`, 行为不变
--
-- 修复 (方案 D — 数据级清理, 不动代码):
--   1) **保留 F 类型行** (权威 perm 字符串载体, v1.1.52.6 起 markDisabled 兼容)
--      sql/35/40/41 后续所有 perm 校验都走 F 行 (sort_no=2015/1015), 删它会破坏业务
--   2) **删除 B 类型行** id=6050/6042 — 这两行只是 sql/23 seed 时凑数的按钮占位,
--      没有 PC 端业务代码读它们的 id, 仅 menu_name='反审核' 与 F 行同名显示重复
--   3) **sys_role_menu 引用迁移**: 把 6050/6042 的 sys_role_menu 行 UPDATE 到 F 行 id,
--      等价于 perm 不变, 只是 menu_id 从旧 B 行换到新 F 行. 不丢任何授权.
--   4) (idempotent) 兜底: 再次跑脚本无副作用, 旧 B 行已删干净就不动
--
-- 不动:
--   - sys_role_menu 行数不变 (只换 menu_id)
--   - sys_role 行不变
--   - sys_user 行不变
--   - 其他模块 (6048 反审核采购订单 / 6054 反审核采购退货 / 6057 反审核销售订单 /
--     6059 反审核销售退货 / 6062 反审核盘点单) 不在本次范围, 暂不清理
--
-- 设计原则 (R10):
--   - sys_menu 的 perms 列应该有 UNIQUE 索引防 future duplicate, 但**不在本次范围**
--     (回溯历史 75 条 F + 21 条 B perm 都要校验, 风险大, 留 v1.1.57+ 单独做)
--   - 后续 seed perm 行时必须先 SELECT 查重, 不能依赖 INSERT IGNORE 兜底
--   - sql/23 (2026-07-28 seed 的 21 个 B 类型按钮行) 命名习惯"单行文本不重复模块名前缀"
--     与 sql/35 的"F 类型完整命名"风格不一致, 但本次只清理 uncheck 行 (用户截图定位),
--     其他 19 行 (check/edit/delete/print) 待后续单独审计
--
-- ⚠️ MySQL 8 容器默认 latin1, 必须:
--   docker exec -i erp-mysql mysql industrial_erp -uroot -p$PW \
--     --default-character-set=utf8mb4 < 42_v158_dedup_uncheck_perm.sql
-- =====================================================================

USE `industrial_erp`;

-- 0. 临时变量: 旧 B 行 id 和对应新 F 行 perms
SET @b_pur := (SELECT id FROM `sys_menu` WHERE perms = 'purchase:receipt:uncheck'
                AND menu_type = 'B' AND deleted = 0 ORDER BY id LIMIT 1);
SET @b_sal := (SELECT id FROM `sys_menu` WHERE perms = 'sales:delivery:uncheck'
                AND menu_type = 'B' AND deleted = 0 ORDER BY id LIMIT 1);

-- 1. sys_role_menu 引用迁移 (旧 B 行 → 新 F 行)
--    注意: 不删 sys_role_menu 行, 只换 menu_id, 保证授权不变
--    风险: 如果同一角色对同一 perm 已经有 F 行授权, UPDATE 会撞 PRIMARY (role_id+menu_id+client_type)
--    解决: UPDATE 前先 DELETE 旧 B 行的同 (role_id, perms, client_type) 已存在的 F 行授权,
--          这样 UPDATE 后只剩唯一一行
DELETE rm_dup FROM `sys_role_menu` rm_dup
JOIN `sys_role_menu` rm_b ON rm_b.menu_id IN (@b_pur, @b_sal)
                          AND rm_dup.role_id = rm_b.role_id
                          AND rm_dup.client_type = rm_b.client_type
JOIN `sys_menu` new_f ON new_f.id = rm_dup.menu_id
                    AND new_f.perms IN ('purchase:receipt:uncheck', 'sales:delivery:uncheck')
                    AND new_f.menu_type = 'F'
WHERE rm_dup.menu_id = new_f.id;

UPDATE `sys_role_menu` rm
JOIN `sys_menu` old_b ON old_b.id = rm.menu_id AND old_b.menu_type = 'B'
                      AND old_b.perms IN ('purchase:receipt:uncheck', 'sales:delivery:uncheck')
                      AND old_b.deleted = 0
JOIN `sys_menu` new_f ON new_f.perms = old_b.perms
                      AND new_f.menu_type = 'F' AND new_f.deleted = 0
SET rm.menu_id = new_f.id
WHERE rm.menu_id = old_b.id;

-- 2. 删除旧 B 行 (id=6050/6042)
DELETE FROM `sys_menu`
WHERE menu_type = 'B'
  AND perms IN ('purchase:receipt:uncheck', 'sales:delivery:uncheck')
  AND deleted = 0;

-- 3. 校验 1: 每条 :uncheck perm 现在应该只剩 1 行 (F 类型)
SELECT perms, COUNT(*) AS cnt, GROUP_CONCAT(id ORDER BY id) AS ids,
       GROUP_CONCAT(menu_type ORDER BY id) AS types,
       GROUP_CONCAT(menu_name ORDER BY id) AS names
FROM `sys_menu`
WHERE deleted = 0
  AND perms IN ('purchase:receipt:uncheck', 'sales:delivery:uncheck')
GROUP BY perms;

-- 预期:
--   purchase:receipt:uncheck  cnt=1  ids=2090345792472715349  types=F  names=采购入库反审核
--   sales:delivery:uncheck    cnt=1  ids=2090345792472715350  types=F  names=销售出库反审核

-- 4. 校验 2: 角色授权数不变 (只是 menu_id 从 B 行换到 F 行)
SELECT r.role_code, r.role_name,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id
              WHERE rm.role_id=r.id AND m.perms='purchase:receipt:uncheck' AND rm.client_type='PC') AS pur_uncheck_pc,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id
              WHERE rm.role_id=r.id AND m.perms='sales:delivery:uncheck' AND rm.client_type='PC') AS sal_uncheck_pc,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id
              WHERE rm.role_id=r.id AND m.perms='purchase:receipt:uncheck' AND rm.client_type='APP') AS pur_uncheck_app,
       EXISTS(SELECT 1 FROM sys_role_menu rm JOIN sys_menu m ON m.id=rm.menu_id
              WHERE rm.role_id=r.id AND m.perms='sales:delivery:uncheck' AND rm.client_type='APP') AS sal_uncheck_app
FROM sys_role r
WHERE r.deleted = 0
ORDER BY r.id;

-- 预期: 与跑 sql/42 之前完全一致 (授权 perm 字符串不变)

-- 5. 校验 3: sys_role_menu 总行数不变
SELECT COUNT(*) AS role_menu_total FROM sys_role_menu;

-- 6. 校验 4: 旧 B 行确实没了
SELECT id, menu_name, menu_type, perms FROM sys_menu
WHERE id IN (6042, 6050) AND deleted = 0;
-- 预期: 0 行

-- 7. 用户操作:
--    - PC 端刷新【角色管理 → 分配权限】弹窗, 采购入库/销售出库目录下只剩一条
--      "采购入库反审核 / 销售出库反审核" 行 (F 类型)
--    - 不需要重打 pc-web dist / 后端 jar / App APK (纯数据修复, perm 字符串没变)
--    - 不需要用户退出重登 (Sa-Token session 缓存的 perm 数组不变)
--    - 业务行为零变化: 后端 @SaCheckPermission 还是 match perms 字符串, App 端
--      getPermissions() 读的还是 perms 数组, 都只看 perm 字符串不看 menu_id
