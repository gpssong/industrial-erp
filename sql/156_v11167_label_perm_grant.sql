-- =====================================================================
-- v1.1.167 — 标签层权限点（3 个 base:label:* perm）
--   规格: docs/32_标签层设计规格.md §8; 计划: docs/33_标签层实施计划.md Task A14
--
-- ⚠️ 只在 .150 主库跑, .32/阿里云靠复制自动获得。
--    本项目 binlog_format=ROW 且 .32 是 .150 的下游 —— 本脚本含 INSERT,
--    一旦先在 canary 试跑, 再灌主库会把主库事件复制到 .32 撞 1062
--    → Slave_SQL_Running: No, 备库从此不跟主库 (sql/142/143/152 三次真实翻车)。
--    canary 只做**只读演练** (见计划 Step 1)。
--
-- 只读取证记录 (2026-11-05, 主控在 .150 只读取证, 本脚本编写时据此定稿):
--   · .150 主库 sys_menu **无名为「工作台」的行** (v1.1.14x 已改菜单名), 载体实为
--     id=1 (1 经营驾驶舱 10001 M '' status=1); .32 有「工作台」行且同为 id=1
--     → 下方 IFNULL(...,1) 动态解析在两站都落在 1。
--   · SELECT id,perms,status,deleted FROM sys_menu WHERE perms LIKE 'base:label%'
--     → .150 零行 (无试跑残留, 无需先删净)。
--   · SELECT id FROM sys_menu WHERE id IN (4901,4902,4903) → 零行。**注意这条查的是主键 id,
--     不是 sort_no** —— sys_menu 只有 PRIMARY KEY(id) + idx_sys_menu_parent, sort_no 无唯一键,
--     所以即便 4901~4903 与别的行撞号也只是同级排序抖动, 不会报错 (下方仍用 4901~4903)。
--   · .150 在用角色 11 个 (SUPER_ADMIN/PURCHASE_MGR/SALES_MGR/WAREHOUSE_MGR/
--     PRODUCTION_MGR/FINANCE/zdg/cmg/HERMES_BOT/jyy/wby) —— 授权口径按下方
--     「镜像源 perm 反查既有授权行」实现, **不硬编码角色清单**。
--
-- 为什么必须「启用行 + 授权行」同批做 (铁律 4, v1.1.137 飞鹅打印卡半年的根因):
--   · 后端 @SaCheckPermission → SysUserMapper.selectPermsByUserId —— 不过滤 status, 不过滤端
--   · 登录下发 / 前端 hasPerm → SysMenuMapper.selectPermsByUserIdAndClient —— status=1 且 client_type IN ('BOTH',端)
--   只做一半 ⇒ 「接口调得动、按钮看不见」或反过来「按钮满屏、一点 403」。
--
-- 载体 parent 挂「工作台」—— F 型 perm 载体 parent_id=0 会挤到 root 找不到 (技能铁律)。
--   本站与飞牛的工作台 id 可能不同 (sql/129 踩过), 所以**先按 menu_name 动态解析**。
--   ⚠ 解析不到时兜底 1 —— 这在两站都对 (.150 的 id=1 是「经营驾驶舱」即 v1.1.14x 改名后的
--   工作台, .32 的 id=1 就叫「工作台」), 但**在全新库上等于硬编码**: 1 若不存在, 三行会挂在
--   悬空 parent 上 (sys_menu 无外键, 不报错, 只是角色树里授不到)。所以下方第 1 段前有一句
--   `SELECT @dash` 打印实际落点, 灌库时**先看这一行**再继续。
--
-- 授权口径 (拍板): **只镜像、不创造新权限面** ——
--   base:label:list / base:label:print 镜像于「该单据本身的查看权限」:
--     已能看 成品入库(production:finished-in:list) / 采购入库(purchase:receipt:list) /
--     销售出库(sales:delivery:list) 的角色才补标签行, 同端镜像。
--   base:label:template 镜像于「系统配置查看」(system:config:list) —— 模板设计是后台职能,
--     一线工人不该改模板。
--   本次不为标签新建业务角色。
--
-- 字符集: 含中文 menu_name, 必须 --default-character-set=utf8mb4, 跑完抽查 SELECT HEX(menu_name)。
-- 幂等: sys_menu 按 perms 探测 + 第 1.5 段窄 UPDATE 自愈 (行已存在但 status=0 也拉回启用),
--       sys_role_menu 用 INSERT IGNORE + NOT EXISTS 双保险 (PK = role_id + menu_id + client_type)。
-- =====================================================================
USE `industrial_erp`;

SELECT '--- 0. 灌前现状 (三个 perm 应为 0 行) ---' AS step;
SELECT id, parent_id, menu_name, menu_type, perms, status, deleted
FROM sys_menu WHERE perms LIKE 'base:label:%';

-- ------------------------------------------------------------------
-- 1. 三个 F 型 perm 载体 (status=1 才可授权/下发; is_visible=0 不进侧栏,
--    页面可见性由 PC 路由 meta.perm 与 sys_role_menu 派生)
-- ------------------------------------------------------------------
SET @dash := IFNULL((SELECT id FROM sys_menu WHERE menu_name = '工作台' AND deleted = 0 ORDER BY id LIMIT 1), 1);
-- 灌库时人工核对这一行: 期望落在「工作台/经营驾驶舱」的真实 id (两站实测都是 1)。
SELECT @dash AS dash_carrier_id,
       (SELECT menu_name FROM sys_menu WHERE id = @dash) AS dash_carrier_name;

INSERT INTO sys_menu (parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status)
SELECT @dash, '标签数据查看', 'F', '', '', 'base:label:list', '', 4901, 0, 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'base:label:list' AND deleted = 0);

INSERT INTO sys_menu (parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status)
SELECT @dash, '标签模板维护', 'F', '', '', 'base:label:template', '', 4902, 0, 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'base:label:template' AND deleted = 0);

INSERT INTO sys_menu (parent_id, menu_name, menu_type, path, component, perms, icon, sort_no, is_visible, status)
SELECT @dash, '标签打印', 'F', '', '', 'base:label:print', '', 4903, 0, 1 FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'base:label:print' AND deleted = 0);

-- ------------------------------------------------------------------
-- 1.5 自愈: 把「已存在但被停用/挂错父级」的载体拉回 status=1
--   上面三条 INSERT 的 NOT EXISTS 只探测 deleted=0 —— 若某站已有一行 status=0
--   (v1.1.137 飞鹅 sql/145 就是这个形态: 行在、停用着 ⇒ 前端永远拿不到),
--   INSERT 会被跳过, 而下一段镜像只 CROSS JOIN status=1 的行 ⇒ **静默授 0 行**。
--   这里补一条窄 UPDATE: 只动这三个 perm、只动未软删的行, 不新建权限面。
-- ------------------------------------------------------------------
UPDATE sys_menu SET status = 1, parent_id = @dash, is_visible = 0
WHERE deleted = 0 AND perms IN ('base:label:list', 'base:label:template', 'base:label:print');

-- ------------------------------------------------------------------
-- 2. 授权: 镜像源 perm 的**同端**授权行
--    base:label:list + base:label:print ← 三张单据的 :list
--    base:label:template ← system:config:list
-- ------------------------------------------------------------------
SET @has_ct := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_role_menu' AND COLUMN_NAME = 'client_type'
);

SET @sql_print := IF(@has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
   SELECT DISTINCT rm.role_id, m.id, rm.client_type
   FROM sys_role_menu rm
   JOIN sys_menu src ON src.id = rm.menu_id AND src.deleted = 0 AND src.status = 1
        AND src.perms IN (''production:finished-in:list'', ''purchase:receipt:list'', ''sales:delivery:list'')
   CROSS JOIN sys_menu m ON m.perms IN (''base:label:list'', ''base:label:print'') AND m.deleted = 0 AND m.status = 1
   JOIN sys_role r ON r.id = rm.role_id AND r.deleted = 0 AND r.status = 1',
  'INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
   SELECT DISTINCT rm.role_id, m.id
   FROM sys_role_menu rm
   JOIN sys_menu src ON src.id = rm.menu_id AND src.deleted = 0 AND src.status = 1
        AND src.perms IN (''production:finished-in:list'', ''purchase:receipt:list'', ''sales:delivery:list'')
   CROSS JOIN sys_menu m ON m.perms IN (''base:label:list'', ''base:label:print'') AND m.deleted = 0 AND m.status = 1
   JOIN sys_role r ON r.id = rm.role_id AND r.deleted = 0 AND r.status = 1');
PREPARE s FROM @sql_print; EXECUTE s; DEALLOCATE PREPARE s;

SET @sql_tpl := IF(@has_ct > 0,
  'INSERT IGNORE INTO sys_role_menu (role_id, menu_id, client_type)
   SELECT DISTINCT rm.role_id, m.id, rm.client_type
   FROM sys_role_menu rm
   JOIN sys_menu src ON src.id = rm.menu_id AND src.deleted = 0 AND src.status = 1
        AND src.perms = ''system:config:list''
   CROSS JOIN sys_menu m ON m.perms = ''base:label:template'' AND m.deleted = 0 AND m.status = 1
   JOIN sys_role r ON r.id = rm.role_id AND r.deleted = 0 AND r.status = 1',
  'INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
   SELECT DISTINCT rm.role_id, m.id
   FROM sys_role_menu rm
   JOIN sys_menu src ON src.id = rm.menu_id AND src.deleted = 0 AND src.status = 1
        AND src.perms = ''system:config:list''
   CROSS JOIN sys_menu m ON m.perms = ''base:label:template'' AND m.deleted = 0 AND m.status = 1
   JOIN sys_role r ON r.id = rm.role_id AND r.deleted = 0 AND r.status = 1');
PREPARE s FROM @sql_tpl; EXECUTE s; DEALLOCATE PREPARE s;

-- ------------------------------------------------------------------
-- 3. 校验 (人工核对每一行, 不符就停下来查, 别接着发布)
-- ------------------------------------------------------------------
SELECT '--- 4. sys_menu 三行 (期望 3 行, status=1, parent_id=工作台 id) ---' AS step;
SELECT id, parent_id, menu_name, menu_type, perms, sort_no, is_visible, status
FROM sys_menu WHERE perms LIKE 'base:label:%' AND deleted = 0 ORDER BY sort_no;

SELECT '--- 5. 中文没乱码 (期望 E6A087… 之类 utf8mb4 字节, 不能是 C3A9 前缀) ---' AS step;
SELECT perms, HEX(menu_name) AS hex_name FROM sys_menu WHERE perms LIKE 'base:label:%' AND deleted = 0;

SELECT '--- 6. 各角色各端拿到什么 ---' AS step;
SELECT r.role_code, GROUP_CONCAT(CONCAT(m.perms, '@', rm.client_type) ORDER BY m.perms SEPARATOR '  |  ') AS grants
FROM sys_role_menu rm
JOIN sys_role r ON r.id = rm.role_id AND r.deleted = 0
JOIN sys_menu m ON m.id = rm.menu_id
WHERE m.perms LIKE 'base:label:%'
GROUP BY r.role_code ORDER BY r.role_code;

-- ------------------------------------------------------------------
-- 7. (仅当在 canary 本地试跑过才需要) 灌主库前清掉 canary 上本次写入的行, 防复制 1062:
--    DELETE rm FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id WHERE m.perms LIKE 'base:label:%';
--    DELETE FROM sys_menu WHERE perms LIKE 'base:label:%';
--    —— 本脚本设计上**不在 canary 跑**, 这段只是万一跑过后的补救, 不是常规步骤。
-- ------------------------------------------------------------------
