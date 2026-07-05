-- ============================================================
-- 修复历史双重 UTF-8 编码 (Double-Mojibake) 的中文数据
-- 原因: 旧版本 JDBC URL characterEncoding=utf8 + 响应未强制 UTF-8,
--       导致 Java 字符串 → latin1 字节 → MySQL → 再读出时被当 latin1 解码 → 双重乱码
-- 修复: 把每列"按 latin1 重新装回 BINARY, 再按 utf8 解码"
-- 安全: 用 SELECT 预览, UPDATE 前 WHERE 子句限制包含乱码特征的行
-- ============================================================

SET NAMES utf8mb4;

-- 预览受影响行数
SELECT 'sys_menu 菜单名称' AS tbl, COUNT(*) AS mojibake_rows
FROM sys_menu
WHERE menu_name REGEXP 'Ã|Â|Ä|Å|Æ|Ç|È|É|Ê|Ë|ä|ö|ü';

-- 修复 sys_menu
UPDATE sys_menu
SET menu_name = CONVERT(CAST(CONVERT(menu_name USING latin1) AS BINARY) USING utf8)
WHERE menu_name REGEXP 'Ã|Â|Ä|Å|Æ|Ç|È|É|Ê|Ë|ä|ö|ü';

-- 修复 sys_dept 部门名称
UPDATE sys_dept
SET dept_name = CONVERT(CAST(CONVERT(dept_name USING latin1) AS BINARY) USING utf8)
WHERE dept_name REGEXP 'Ã|Â|Ä|Å|Æ|Ç|È|É|Ê|Ë|ä|ö|ü';

-- 修复 sys_dept 部门负责人 (v1.0.4+ 显示在右上角, 容易漏)
UPDATE sys_dept
SET leader = CONVERT(CAST(CONVERT(leader USING latin1) AS BINARY) USING utf8)
WHERE leader REGEXP 'Ã|Â|Ä|Å|Æ|Ç|È|É|Ê|Ë|ä|ö|ü';

-- 修复 sys_role 角色名
UPDATE sys_role
SET role_name = CONVERT(CAST(CONVERT(role_name USING latin1) AS BINARY) USING utf8)
WHERE role_name REGEXP 'Ã|Â|Ä|Å|Æ|Ç|È|É|Ê|Ë|ä|ö|ü';

-- 修复 sys_user 昵称/姓名
UPDATE sys_user
SET nickname = CONVERT(CAST(CONVERT(nickname USING latin1) AS BINARY) USING utf8)
WHERE nickname REGEXP 'Ã|Â|Ä|Å|Æ|Ç|È|É|Ê|Ë|ä|ö|ü';

UPDATE sys_user
SET real_name = CONVERT(CAST(CONVERT(real_name USING latin1) AS BINARY) USING utf8)
WHERE real_name REGEXP 'Ã|Â|Ä|Å|Æ|Ç|È|É|Ê|Ë|ä|ö|ü';

-- 基础资料
UPDATE base_product SET product_name = CONVERT(CAST(CONVERT(product_name USING latin1) AS BINARY) USING utf8)
WHERE product_name REGEXP 'Ã|Â|Ä|Å|Æ|Ç|È|É|Ê|Ë|ä|ö|ü';
UPDATE base_product SET spec = CONVERT(CAST(CONVERT(spec USING latin1) AS BINARY) USING utf8)
WHERE spec REGEXP 'Ã|Â|Ä|Å|Æ|Ç|È|É|Ê|Ë|ä|ö|ü';

UPDATE base_customer SET customer_name = CONVERT(CAST(CONVERT(customer_name USING latin1) AS BINARY) USING utf8)
WHERE customer_name REGEXP 'Ã|Â|Ä|Å|Æ|Ç|È|É|Ê|Ë|ä|ö|ü';

UPDATE base_supplier SET supplier_name = CONVERT(CAST(CONVERT(supplier_name USING latin1) AS BINARY) USING utf8)
WHERE supplier_name REGEXP 'Ã|Â|Ä|Å|Æ|Ç|È|É|Ê|Ë|ä|ö|ü';

UPDATE base_warehouse SET warehouse_name = CONVERT(CAST(CONVERT(warehouse_name USING latin1) AS BINARY) USING utf8)
WHERE warehouse_name REGEXP 'Ã|Â|Ä|Å|Æ|Ç|È|É|Ê|Ë|ä|ö|ü';

-- 业务表 (按需)
UPDATE pur_order SET supplier_name = CONVERT(CAST(CONVERT(supplier_name USING latin1) AS BINARY) USING utf8)
WHERE supplier_name REGEXP 'Ã|Â|Ä|Å|Æ|Ç|È|É|Ê|Ë|ä|ö|ü';
UPDATE sal_order SET customer_name = CONVERT(CAST(CONVERT(customer_name USING latin1) AS BINARY) USING utf8)
WHERE customer_name REGEXP 'Ã|Â|Ä|Å|Æ|Ç|È|É|Ê|Ë|ä|ö|ü';

-- 验证
SELECT menu_name FROM sys_menu WHERE id IN (1, 2, 201, 301, 401, 501, 601, 701, 801);