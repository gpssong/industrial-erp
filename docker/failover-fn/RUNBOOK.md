# ERP 热备故障切换 Runbook (飞牛 NAS)

> **目的**: 当主站 Synology DS918+ 不可服务时, 飞牛 NAS (n150.93gushi.com) 自动接管
> **位置**: 飞牛 NAS /volume2/erp-system/
> **维护人**: ERP 运维负责人

## 1. 架构概览

```
主站 (Synology DS918+)                  飞牛 NAS (n150.93gushi.com)
home.93gushi.com:8088                    n150.93gushi.com:8088
MySQL (binlog ROW)        ───复制───>    MySQL (read-only replica)
Redis (AOF)               ───同步───>    Redis (replica)
后端 + 前端                              后端 + 前端 (standby)
```

## 2. 状态机

| 状态 | 含义 | 触发条件 |
|------|------|---------|
| NORMAL | 主站正常, 飞牛 standby | 主站健康检查通过 |
| FAILING | 主站临时不可用 | 连续 1-2 次健康检查失败 |
| FAILED | 主站已经故障, 飞牛接管 | 连续 3 次超时, 触发切换 |
| RECOVERING | 主站恢复, 飞牛等待回切 | 主站健康检查恢复 |

## 3. 故障切换流程

### 3.1 自动检测 (failover.sh loop 模式)

```
[飞牛 NAS] 每 60 秒检查主站
    ↓
连续 3 次失败 (超时 10 秒)
    ↓
触发 trigger_failover()
    ↓
1. 启动备用 backend + pc-web 容器
2. 等待 30 秒, 验证 Backend 就绪
3. 调用 dns-switch.sh to-backup
4. 通知企业微信
```

### 3.2 手动触发 (应急)

如果自动监控失败, 手动切换:

```bash
ssh gpssong@n150.93gushi.com  # 密码: 850225sonG

# 立即切换
sudo /volume2/erp-system/failover.sh trigger

# 查看状态
sudo /volume2/erp-system/failover.sh status
```

### 3.3 回切流程

主站修复后, 自动回切:

```bash
# 手动回切
sudo /volume2/erp-system/failover.sh rollback
```

回切步骤:
1. 验证主站健康
2. 切换 DNS 回主站
3. 等待 15 分钟 DNS 缓存过期
4. 停止备用服务
5. 通知

## 4. 关键脚本

| 脚本 | 位置 | 功能 |
|------|------|------|
| failover.sh | /volume2/erp-system/ | 主故障切换控制器 |
| dns-switch.sh | /opt/industrial-erp/scripts/ | DNS 切换 (DNSPod + Cloudflare) |
| init-mysql-replica.sh | /volume2/erp-system/ | MySQL 主从复制初始化 |
| sync-to-fnos.sh | 主 NAS /opt/industrial-erp/scripts/ | 数据同步到飞牛 |

## 5. 应急联系

| 角色 | 联系方式 | 升级路径 |
|------|---------|---------|
| ERP 运维 | xxx-xxxx | 第一响应 |
| DBA | xxx-xxxx | 数据库故障 |
| 网络 | xxx-xxxx | DNS 故障 |
| NAS 厂商 | 飞牛官方 | 硬件故障 |

## 6. 常见问题

### Q1: 切换后用户无法访问?

- **DNS 缓存**: 等待 5-15 分钟, 或切换备用域名 `n150.93gushi.com:8088`
- **浏览器缓存**: 清除 DNS 缓存 (Windows: `ipconfig /flushdns`, Mac: `sudo dscacheutil -flushcache`)
- **检查飞牛服务**: `ssh gpssong@n150.93gushi.com "docker ps"`

### Q2: MySQL 主从延迟过大?

```bash
# 查看主从状态
mysql -uroot -perp_root_pwd -e "SHOW SLAVE STATUS\G"

# Slave_IO_Running: Yes  (网络 + binlog 接收)
# Slave_SQL_Running: Yes (SQL 应用)
# Seconds_Behind_Master: 0 (延迟 < 5s 正常)
```

### Q3: 飞牛容器无法启动?

```bash
ssh gpssong@n150.93gushi.com
docker logs erp-backend-failover
docker logs erp-mysql-failover
docker logs erp-pc-web-failover
```

### Q4: 怎么回切主站?

等主 NAS 修复后:
1. 验证主站 `curl https://home.93gushi.com:8088/api/auth/captcha`
2. SSH 飞牛执行 `sudo /volume2/erp-system/failover.sh rollback`
3. 等待 DNS 切换 (15 分钟 TTL)
4. 通知用户

## 7. 预防性维护

### 每周
- [ ] 检查 MySQL 主从延迟 (`Seconds_Behind_Master`)
- [ ] 检查飞牛磁盘使用率 < 80%
- [ ] 验证最近一次数据同步时间

### 每月
- [ ] 演练一次故障切换 (在非工作时间)
- [ ] 检查备份完整性
- [ ] 更新 Runbook

### 每季度
- [ ] 测试回切流程
- [ ] 评估热备容量
- [ ] 更新告警阈值

## 8. 应急升级路径

```
自动检测故障 (5 分钟内)
  ↓
飞牛 failover.sh 触发切换
  ↓
DNS 切换 + 备用服务启动 (总计 5-10 分钟)
  ↓
通知用户 + 运维团队
  ↓
运维诊断主站原因
  ↓
修复主 NAS
  ↓
数据反向同步 (主从)
  ↓
执行回切
  ↓
通知用户 + 验证
```

总 SLA 目标: **故障切换 < 15 分钟**

## 9. 容量规划

| 资源 | 当前用量 | 飞牛容量 | 警告阈值 |
|------|---------|---------|---------|
| 磁盘 /volume2 | 50 GB | 500 GB | 80% |
| MySQL 数据 | 5 GB | 100 GB | 70% |
| Redis 内存 | 200 MB | 2 GB | 80% |
| 带宽 | 5 Mbps | 100 Mbps | 70% |
