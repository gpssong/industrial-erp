-- =====================================================================
-- v1.1.117 hotfix-3: base_product_unit.unit_id = 0 数据回填
-- ---------------------------------------------------------------------
-- 背景 (v1.1.117 hotfix-2 踩坑根因):
--   base_product_unit 有 18/174 行 unit_id = **0** (不是 NULL), unit_name 是
--   真实的 (公斤/卷/只/片/箱), 只是 unit_id 指向一条不存在的 base_unit.
--   JS `u.unitId &&` / `x.unitId > 0` 把 0 当 falsy → 整行被丢 → 前端
--   显示「该商品未配置单位」; PC 端更糟, find 落空后静默 fallback 到
--   base_unit 全表第一条 (kg), 单位被默默选错且不报错.
--   前端已在 hotfix-2 修好 (只认 unitName 非空), 本脚本治数据层的根.
--
-- 决策 (用户已拍板): 公斤 → 并入已有 kg; 片 → 新建字典行
--   - 「公斤」就是 kg 的中文, 飞牛已有先例: base_product_unit id
--     2099763778742202370 就是「公斤 → unit_id=kg, conversion_rate=10」.
--     不建第二个同义单位, 否则同一物理量两个 unit_id, 折算口径会分裂.
--   - 「片」字典里完全没有, 必须新建.
--
-- ⚠️ 执行前提:
--   1) 本项目外键 id 从 1 开始, 所以 unit_id=0 一定是脏数据, 不是合法值.
--   2) base_unit 有 UNIQUE KEY (unit_code, deleted) — 重复执行本脚本安全
--      (新增片用 NOT EXISTS 守门, 回填按名字匹配唯一存活行).
--   3) 中文查询必须 --default-character-set=utf8mb4 (MySQL 容器默认 latin1).
--   4) 部署顺序: 飞牛 canary 先行, 验证后同步 home.
--
-- 用法:
--   docker cp 本文件 <mysql容器>:/tmp/ && \
--   docker exec <mysql容器> mysql -uroot -p<pwd> --default-character-set=utf8mb4 \
--     -D industrial_erp -e 'source /tmp/127_v1117_hotfix3_unit_id_zero_backfill.sql'
-- =====================================================================

-- ---------- 0. 备份 (必做, 永远先备份原值再动) ----------
-- CREATE TABLE IF NOT EXISTS bak_bpu_unitid0_20261023 LIKE base_product_unit;
-- INSERT INTO bak_bpu_unitid0_20261023
--   SELECT * FROM base_product_unit WHERE deleted = 0 AND unit_id = 0;

-- ---------- 1. 新建缺失的单位字典行「片」 ----------
-- unit_code 用拼音 PIAN, 与现有 JUAN/XIANG/ZHI/BAO/GE/TAO 风格一致.
-- NOT EXISTS 守门: 已存在(哪怕软删)就不重复插, 避免撞 UNIQUE(unit_code, deleted).
INSERT INTO base_unit (unit_code, unit_name, status, deleted)
SELECT 'PIAN', '片', 1, 0
WHERE NOT EXISTS (
  SELECT 1 FROM (SELECT unit_code, deleted FROM base_unit) x
  WHERE x.unit_code = 'PIAN' AND x.deleted = 0
);

-- ---------- 2. 回填 base_product_unit.unit_id ----------
-- 按 unit_name 匹配**存活**(deleted=0)的字典行. base_unit 里"重复"的名字
-- (卷/箱/只/包/个/套 各两条) 旧的那批全是 deleted=1 软删, 存活名唯一, 不歧义;
-- 即便不唯一, MIN(id) 也保证确定性 (MySQL 不允许在 UNION 分支里直接写
-- ORDER BY ... LIMIT, 所以用 GROUP BY + MIN(id) 一次成型).
-- 「公斤」不新建字典行, 直接并入 kg (见文件头决策) — 用 m.unit_name='kg' 那一档匹配.
UPDATE base_product_unit u
JOIN (
  SELECT unit_name, MIN(id) AS target_id
  FROM base_unit
  WHERE deleted = 0 AND unit_name IN ('kg', '片', '卷', '只', '箱')
  GROUP BY unit_name
) m ON (m.unit_name = u.unit_name OR (u.unit_name = '公斤' AND m.unit_name = 'kg'))
SET u.unit_id = m.target_id
WHERE u.deleted = 0 AND u.unit_id = 0
  AND u.unit_name IN ('公斤', '片', '卷', '只', '箱');

-- ---------- 3. 回填 base_product.main_unit_id ----------
-- 9 个商品 main_unit_id=0 (透明胶带/机用打包带/带鱼袋/蓝片子...), 它们的主单位
-- 就是 base_product_unit 里 is_main=1 的那条.
-- ⚠️ 有 1 个商品 (透明胶带 2110288401078497282) 的单位行**没标 is_main=1**, 只认
--    is_main 会落空 → 所以加兜底: 无主单位行时取 MIN(id) 那行当主单位.
--    (前端 hotfix-2 同样有 usable[0] 兜底, 两侧行为对齐.)
UPDATE base_product p
JOIN (
  SELECT product_id, unit_id FROM (
    SELECT u.product_id, u.unit_id,
           ROW_NUMBER() OVER (PARTITION BY u.product_id
                              ORDER BY u.is_main DESC, u.id) AS rn
    FROM base_product_unit u
    WHERE u.deleted = 0 AND u.unit_id <> 0
  ) ranked WHERE rn = 1
) t ON t.product_id = p.id
SET p.main_unit_id = t.unit_id
WHERE p.deleted = 0 AND (p.main_unit_id = 0 OR p.main_unit_id IS NULL);

-- ---------- 4. 验证 ----------
-- 应全部为 0:
SELECT '残留 unit_id=0 (期望 0)' AS chk, COUNT(*) c FROM base_product_unit
WHERE deleted = 0 AND unit_id = 0;
SELECT '残留 main_unit_id=0/NULL (期望 0)' AS chk, COUNT(*) c FROM base_product
WHERE deleted = 0 AND (main_unit_id = 0 OR main_unit_id IS NULL);
-- 应为 0 (回填后不该再有悬空外键):
SELECT '悬空外键 (期望 0)' AS chk, COUNT(*) c FROM base_product_unit u
LEFT JOIN base_unit b ON b.id = u.unit_id
WHERE u.deleted = 0 AND b.id IS NULL;
-- 回填明细
SELECT b.unit_name, COUNT(*) c FROM base_product_unit u
JOIN base_unit b ON b.id = u.unit_id
WHERE u.deleted = 0 AND u.unit_id IN (
  (SELECT id FROM base_unit WHERE unit_name='kg' AND deleted=0),
  (SELECT id FROM base_unit WHERE unit_name='片' AND deleted=0)
) GROUP BY b.unit_name;