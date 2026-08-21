# ERP 热备方案 (飞牛 NAS)

## 文件结构

```
failover-fn/
├── docker-compose.failover.yml   # 飞牛 NAS 容器编排
├── init-mysql-replica.sh         # MySQL 主从复制初始化
├── failover.sh                    # 自动故障切换主脚本
├── dns-switch.sh                  # DNS 切换 (DNSPod + Cloudflare)
├── prometheus.yml                 # Prometheus 监控配置
├── erp-alerts.yml                 # 告警规则
├── alertmanager.yml               # 告警处理 (企业微信)
├── RUNBOOK.md                     # 故障切换手册
└── README.md                      # 本文件
```

## 快速部署

### 1. 飞牛 NAS 准备

```bash
# SSH 飞牛
ssh gpssong@n150.93gushi.com  # 密码 850225sonG

# 创建目录
mkdir -p /volume2/erp-system/{mysql-data,redis-data,upload,backup,dist,prometheus,alertmanager,firewall}

# 复制本目录所有文件到飞牛
rsync -av failover-fn/ gpssong@n150.93gushi.com:/volume2/erp-system/
```

### 2. 启动 Monitor 容器

```bash
# 飞牛 NAS
cd /volume2/erp-system
docker compose -f docker-compose.failover.yml up -d prometheus grafana alertmanager failover-watcher
```

### 3. 配置 MySQL 主库

```sql
-- 主库 (Synology DS918+, 192.168.0.150)
CREATE USER 'repl_user'@'%' IDENTIFIED BY 'Repl_2026_Strong!';
GRANT REPLICATION SLAVE ON *.* TO 'repl_user'@'%';
FLUSH PRIVILEGES;
```

### 4. 配置主从复制

```bash
# 飞牛 NAS
docker compose -f docker-compose.failover.yml up -d erp-mysql-failover
docker exec erp-mysql-failover mysql -uroot -perp_root_pwd \
  -e "CHANGE MASTER TO MASTER_HOST='192.168.0.150', MASTER_USER='repl_user', MASTER_PASSWORD='Repl_2026_Strong!', MASTER_LOG_FILE='mysql-bin.000001', MASTER_LOG_POS=4; START SLAVE;"
docker exec erp-mysql-failover mysql -uroot -perp_root_pwd -e "SHOW SLAVE STATUS\G"
```

### 5. 启动自动故障监控

```bash
# 飞牛 NAS
docker compose -f docker-compose.failover.yml up -d failover-watcher
```

### 6. 演练

```bash
# 模拟主站故障 (飞牛 NAS)
ssh gpssong@n150.93gushi.com
sudo /volume2/erp-system/failover.sh trigger

# 验证 (从主 NAS)
curl https://n150.93gushi.com:8088/api/auth/captcha

# 回切
sudo /volume2/erp-system/failover.sh rollback
```

## SLA 目标

| 指标 | 目标 |
|------|------|
| RPO (数据丢失) | < 60 秒 (MySQL 主从复制) |
| RTO (恢复时间) | < 15 分钟 (自动切换) |
| 可用性 | 99.9% (8.76 小时/年) |

## 监控告警

告警渠道:
- Prometheus → Alertmanager → 企业微信 webhook
- 飞牛本地 cron 监控 → 邮件 / 短信

告警规则:
- MySQL 主从延迟 > 5 分钟 → critical
- 主站不可访问 > 3 分钟 → 触发切换
- 磁盘 > 90% → warning
- 容器频繁重启 → warning

## 后续优化

- [ ] Kubernetes 迁移 (K3s 已经够用)
- [ ] 多副本 (跨多个飞牛 NAS)
- [ ] 异地备份 (阿里云 OSS / 腾讯云 COS)
- [ ] 异地双活 (北京 + 上海)
- [ ] 边缘计算 (CDN 缓存静态资源)

## 相关文档

- [RUNBOOK.md](RUNBOOK.md) - 故障切换详细手册
- [../../CLAUDE.md](../../CLAUDE.md) - ERP 系统文档
