-- v1.1.98+: 设备主数据加设备照片 (XK 设备维保「新增设备」加照片上传 + 档案卡片显示)
-- 用户反馈 2026-10-14: 新增设备时缺照片字段, 设备档案卡片用硬编码 unsplash 占位
-- 沿用 /system/upload/file 上传端点, photo_url 存相对路径或完整 URL, 前端 el-image 直接渲染
-- 兼容 MySQL 5.7 (飞牛 canary): comment 不放在列后, 用末尾 COMMENT 块
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'base_equipment' AND COLUMN_NAME = 'photo_url');
SET @sql := IF(@col_exists = 0,
  'ALTER TABLE base_equipment ADD COLUMN photo_url VARCHAR(512) DEFAULT NULL AFTER qrcode',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
ALTER TABLE base_equipment MODIFY COLUMN photo_url VARCHAR(512) DEFAULT NULL COMMENT '设备照片 URL';
