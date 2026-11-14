-- =====================================================================
-- v1.1.188 — 采购入库的存量原料批回填 (mes_batch, batch_type='RAW')
--
-- 为什么要有这一份: XK 批次溯源「原料批」列表只读 mes_batch 里 batch_type='RAW' 的行,
--   而该表此前只有「生产领料/开工」一个写入方 (MesReportService.registerRawOnIssue,
--   批号为空直接跳过), 采购入库审核只写 inv_stock 自由文本批号 ⇒ 全库 RAW 行数=0,
--   溯源页「原料批」恒为空。写入侧已补 (PurReceiptService.check →
--   MesReportService.registerRawOnReceipt), 这份只补历史已审核入库单。
--
-- 口径:
--   · 只认已审核 (bill_status='CHECKED') 且明细批号非空的入库行 —— 批号是批次唯一标识,
--     没批号的库存行本来就无法按批追溯, 不编造。
--   · 同一批号跨多行/多单合并为一行 (mes_batch 唯一键 uniq_mes_batch_no),
--     数量取合计、供应商与来源单取 MIN (单号按日期递增, 即最早那张)。
--   · 幂等: 同批号已存在则跳过 (WHERE NOT EXISTS)。
--   · production_date 留空 —— 原料的真实生产日期在供应商侧
--     (pur_receipt_detail.production_date), 回填脚本没有可靠来源, 不写"今天"。
--
-- ⚠ 只在 .150 主库跑一次！.32 / 阿里云靠复制自动拿到, 不要在它们上面手动执行
--   (先 canary 跑再灌主库会撞 1062, 见 sql/142/143/152 翻车记录)。
-- =====================================================================

INSERT INTO mes_batch (batch_no, batch_type, product_id, supplier_id, receipt_bill_no,
                       status, qty, remark)
SELECT t.batch_no, 'RAW', t.product_id, t.supplier_id, t.bill_no,
       'ACTIVE', t.qty, 'v1.1.188 回填(采购入库)'
FROM (
    SELECT TRIM(d.batch_no)      AS batch_no,
           MIN(d.product_id)     AS product_id,
           MIN(r.supplier_id)    AS supplier_id,
           MIN(r.bill_no)        AS bill_no,
           SUM(d.qty)            AS qty
    FROM pur_receipt_detail d
    JOIN pur_receipt r ON r.id = d.receipt_id
    WHERE d.deleted = 0
      AND r.deleted = 0
      AND r.bill_status = 'CHECKED'
      AND d.batch_no IS NOT NULL
      AND TRIM(d.batch_no) <> ''
    GROUP BY TRIM(d.batch_no)
) t
WHERE NOT EXISTS (
    SELECT 1 FROM mes_batch b
    WHERE b.batch_no = t.batch_no AND b.deleted = 0
);

-- 核对: 回填后应能看到 RAW 行, 且每行都带来源入库单号
-- SELECT batch_no, product_id, supplier_id, receipt_bill_no, qty, remark
--   FROM mes_batch WHERE batch_type = 'RAW' AND deleted = 0 ORDER BY id;
