# NAS 开机自启动配置文档

## 概述

本文档说明如何在群晖 DS918+ (DSM 7.4) 上配置 ERP 系统的开机自启动和自动恢复机制。

## 配置内容

### 1. Docker Compose 重启策略

在 `docker-compose.yml` 中，所有服务已配置 `restart: unless-stopped`：

```yaml
services:
  mysql:
    restart: unless-stopped
  redis:
    restart: unless-stopped
  backend:
    restart: unless-stopped
  pc-web:
    restart: unless-stopped
  app-h5:
    restart: unless-stopped
```

这意味着：
- 容器异常退出时会自动重启
- 除非手动执行 `docker compose down`，否则容器会一直运行

### 2. 开机启动脚本

**位置**: `/usr/local/etc/rc.d/erp-start.sh`

```bash
#!/bin/bash
# ERP System Auto-Start Script
LOGFILE="/volume3/docker/erp-system/startup.log"
COMPOSE_DIR="/volume3/docker/erp-system"

echo "$(date): Starting ERP System..." >> $LOGFILE
sleep 30  # 等待 Docker 服务就绪
cd $COMPOSE_DIR
/usr/local/bin/docker compose up -d >> $LOGFILE 2>&1
echo "$(date): ERP System started." >> $LOGFILE
```

### 3. 健康检查脚本

**位置**: `/var/services/homes/gpssong/erp-healthcheck.sh`

功能：
- 每 5 分钟检查一次所有容器状态
- 如果容器不在运行状态，自动重启
- 检查后端 API 是否响应，如果不响应则重启后端服务

### 4. Cron 定时任务

**位置**: `/etc/cron.d/erp-healthcheck`

```
*/5 * * * * root /var/services/homes/gpssong/erp-healthcheck.sh
```

每 5 分钟执行一次健康检查。

### 5. Sudoers 配置

**位置**: `/etc/sudoers.d/erp-docker`

```
gpssong ALL=(ALL) NOPASSWD: /usr/local/bin/docker, /usr/local/bin/docker-compose
```

允许 gpssong 用户无密码执行 docker 命令。

## 验证配置

### 检查容器状态

```bash
ssh gpssong@192.168.0.150
sudo /usr/local/bin/docker ps --format 'table {{.Names}}\t{{.Status}}'
```

### 查看启动日志

```bash
cat /volume3/docker/erp-system/startup.log
```

### 查看健康检查日志

```bash
cat /volume3/docker/erp-system/healthcheck.log
```

### 手动测试健康检查

```bash
~/erp-healthcheck.sh
```

## 群晖 Web Station 配置

确保群晖 Web Station 的反向代理配置正确：

1. 打开群晖 DSM Web 界面
2. 进入 "控制面板" > "登录门户" > "高级" > "反向代理"
3. 确保以下规则存在：
   - `home.93gushi.com:8088` → `http://localhost:18080` (PC Web)
   - `m.home.93gushi.com:8088` → `http://localhost:18090` (Mobile H5)
   - API 路由 `/api/*` → `http://localhost:8080` (Backend)

## 故障排除

### 容器无法启动

1. 检查 Docker 服务是否运行：
   ```bash
   sudo /usr/local/bin/docker info
   ```

2. 查看容器日志：
   ```bash
   sudo /usr/local/bin/docker logs erp-backend
   ```

3. 手动启动容器：
   ```bash
   cd /volume3/docker/erp-system
   sudo /usr/local/bin/docker compose up -d
   ```

### 健康检查脚本不工作

1. 检查脚本权限：
   ```bash
   ls -la ~/erp-healthcheck.sh
   ```

2. 检查 sudoers 配置：
   ```bash
   sudo -l
   ```

3. 手动执行脚本并查看输出：
   ```bash
   bash -x ~/erp-healthcheck.sh
   ```

## 注意事项

1. **群晖 DSM 更新**: 群晖系统更新可能会重置 `/usr/local/etc/rc.d/` 目录，更新后需要重新检查启动脚本。

2. **Docker 套件更新**: Docker 套件更新后，路径可能会变化，需要检查 `/usr/local/bin/docker` 是否仍然有效。

3. **磁盘空间**: 定期检查 `/volume3/docker/erp-system/` 目录的磁盘使用情况，特别是日志文件。

4. **备份**: 建议定期备份 `/volume3/docker/erp-system/` 目录，包括数据库数据和配置文件。
