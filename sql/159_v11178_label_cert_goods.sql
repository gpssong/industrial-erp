-- sql/159_v11178_label_cert_goods.sql
-- ⚠️ 只在 .150 主库跑（INSERT 新行，canary 先跑会撞 1062）
-- 给 CERT / GOODS_LABEL 两类标签各落一条 80×60 默认模板种子。
-- INSERT IGNORE：重跑不覆盖已有行。
-- 字段清单与各自 ContextLoader 对齐：
--   CERT: productName, productCode, spec, material, barcode, batchNo, productionDate,
--         qty, unitName, shelfLifeDays, inspectionBillNo, inspectionDate, orderNo
--         + masterService 补的 producerName, producerAddress, xkNo, execStandard, storageCondition, inspectorCode
--   GOODS_LABEL: productName, productCode, spec, material, unitName, gramWeight, width,
--         thickness, colorNo, barcode, qrcode, shelfLifeDays
--         + producerName, producerAddress, xkNo, execStandard, storageCondition, inspectorCode

SELECT '--- 1) 合格证默认模板 ---' AS step;
INSERT IGNORE INTO `base_label_template`
 (`id`,`template_code`,`template_name`,`biz_type`,`width_mm`,`height_mm`,`gap_mm`,`dpi`,`direction`,`copies`,`elements`,`is_default`,`status`,`remark`,`create_by`,`update_by`)
VALUES
 (1178000000000000001,'CERT_DEFAULT','合格证 80×60','CERT',80,60,3,203,'NORMAL',1,
 '[{"type":"text","x":20,"y":20,"text":"合格证书","size":32,"bold":true},
  {"type":"line","x":20,"y":55,"w":600,"h":2},
  {"type":"text","x":20,"y":70,"text":"品名","size":20},{"type":"text","x":110,"y":70,"field":"productName","size":20,"required":true},
  {"type":"text","x":20,"y":100,"text":"规格","size":20},{"type":"text","x":110,"y":100,"field":"spec","size":20},
  {"type":"text","x":20,"y":130,"text":"批号","size":20},{"type":"text","x":110,"y":130,"field":"batchNo","size":20,"required":true},
  {"type":"text","x":20,"y":160,"text":"数量","size":20},{"type":"text","x":110,"y":160,"field":"qty","size":20},
  {"type":"text","x":280,"y":160,"field":"unitName","size":20},
  {"type":"text","x":20,"y":190,"text":"生产日期","size":16},{"type":"text","x":130,"y":190,"field":"productionDate","size":16},
  {"type":"text","x":20,"y":220,"text":"检验日期","size":16},{"type":"text","x":130,"y":220,"field":"inspectionDate","size":16},
  {"type":"text","x":20,"y":250,"text":"检验单号","size":16},{"type":"text","x":130,"y":250,"field":"inspectionBillNo","size":16},
  {"type":"text","x":20,"y":280,"text":"工单号","size":16},{"type":"text","x":130,"y":280,"field":"orderNo","size":16},
  {"type":"line","x":20,"y":310,"w":600,"h":2},
  {"type":"text","x":20,"y":325,"text":"检验员","size":16},{"type":"text","x":110,"y":325,"field":"inspectorCode","size":16},
  {"type":"text","x":20,"y":355,"text":"执行标准","size":16},{"type":"text","x":110,"y":355,"field":"execStandard","size":16},
  {"type":"text","x":20,"y":385,"text":"保质期","size":16},{"type":"text","x":110,"y":385,"field":"shelfLifeDays","size":16},
  {"type":"text","x":20,"y":415,"text":"储存条件","size":16},{"type":"text","x":110,"y":415,"field":"storageCondition","size":16},
  {"type":"text","x":20,"y":445,"field":"producerName","size":16,"required":true},
  {"type":"text","x":20,"y":475,"field":"producerAddress","size":14},
  {"type":"text","x":20,"y":505,"field":"xkNo","size":14,"required":true},
  {"type":"barcode","x":380,"y":320,"field":"inspectionBillNo","size":60}]',
  1,1,'v1.1.178 种子；只列 CHECKED OQC + 结论合格，四道闸与 CertLabelContextLoader 一致',1,1);

SELECT '--- 2) 商品标默认模板 ---' AS step;
INSERT IGNORE INTO `base_label_template`
 (`id`,`template_code`,`template_name`,`biz_type`,`width_mm`,`height_mm`,`gap_mm`,`dpi`,`direction`,`copies`,`elements`,`is_default`,`status`,`remark`,`create_by`,`update_by`)
VALUES
 (1178000000000000002,'GOODS_LABEL_DEFAULT','商品标 80×60','GOODS_LABEL',80,60,3,203,'NORMAL',1,
 '[{"type":"text","x":20,"y":20,"field":"productName","size":28,"bold":true,"required":true},
  {"type":"text","x":20,"y":60,"field":"spec","size":24,"required":true},
  {"type":"line","x":20,"y":95,"w":600,"h":2},
  {"type":"text","x":20,"y":110,"text":"编码","size":16},{"type":"text","x":110,"y":110,"field":"productCode","size":16,"required":true},
  {"type":"text","x":20,"y":140,"text":"材质","size":16},{"type":"text","x":110,"y":140,"field":"material","size":16},
  {"type":"text","x":20,"y":170,"text":"克重","size":16},{"type":"text","x":110,"y":170,"field":"gramWeight","size":16},
  {"type":"text","x":20,"y":200,"text":"宽度","size":16},{"type":"text","x":110,"y":200,"field":"width","size":16},
  {"type":"text","x":280,"y":200,"text":"厚度","size":16},{"type":"text","x":380,"y":200,"field":"thickness","size":16},
  {"type":"text","x":20,"y":230,"text":"颜色","size":16},{"type":"text","x":110,"y":230,"field":"colorNo","size":16},
  {"type":"text","x":20,"y":260,"text":"保质期","size":16},{"type":"text","x":110,"y":260,"field":"shelfLifeDays","size":16},
  {"type":"line","x":20,"y":290,"w":600,"h":2},
  {"type":"barcode","x":20,"y":305,"field":"barcode","size":60},
  {"type":"text","x":20,"y":380,"field":"producerName","size":14,"required":true},
  {"type":"text","x":20,"y":410,"field":"producerAddress","size":12},
  {"type":"text","x":20,"y":440,"field":"xkNo","size":12}]',
  1,1,'v1.1.178 种子；一行商品一张标，不按 status 过滤',1,1);

SELECT '--- 验证 ---' AS step;
SELECT id, template_code, biz_type, template_name FROM base_label_template
 WHERE template_code IN ('CERT_DEFAULT','GOODS_LABEL_DEFAULT') AND deleted = 0;
