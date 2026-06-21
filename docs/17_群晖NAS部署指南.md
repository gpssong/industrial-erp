# 群晖 DS918+ 部署指南 (DSM 7.2+)

> 本项目已经过 NAS 适配: `docker-compose.yml` 默认对 8G 内存机型做了调优 (MySQL InnoDB 缓冲池、JVM 堆、Redis maxmemory), mysql/redis 端口不暴露主机, 上传/备份目录已挂载到 `./data/`, 公网入口仅 80 端口 (HTTPS 走 DSM 反代).

## 一、准备工作

### 1.1 硬件建议
- 群晖 DS918+ / DS920+ / DS1019+ (x86_64)
- 内存: **8G 起步, 推荐 16G** (项目默认按 8G 调优)
- 系统盘用 SSD (M.2 NVMe 或 SATA SSD), 数据盘用 NAS HDD
- 启动盘: 至少预留 5G 给 Docker 镜像

### 1.2 软件准备
- DSM 7.2 或更高 (7.0/7.1 也有 Docker 套件, 但操作步骤略有不同)
- Container Manager 套件 (套件中心 → 搜索 "Container")
- 一个域名 (公网访问需要; 局域网用 NAS IP 即可)

## 二、上传项目文件

在 NAS 上:

1. **File Station** → `docker` 共享文件夹 → 新建子目录 `erp-system`
2. 把以下文件/目录传进去 (scp / git clone / 手动拖拽均可):
   ```
   /volume1/docker/erp-system/
   ├── docker-compose.yml
   ├── .env                    (从 .env.example 复制后改密码)
   ├── backend/                # 整个目录 (含 Dockerfile)
   ├── pc-web/                 # 整个目录 (含 Dockerfile)
   └── sql/                    # 数据库初始化脚本, mysql 容器首次启动会自动导入
   ```

> 推荐 `git clone` 方式, 升级时直接 `git pull` 即可.

## 三、配置 .env

### 3.1 复制 + 重命名

File Station → `docker/erp-system/`:
- 设置 → 勾选 **显示隐藏文件** (`.` 开头的文件默认看不到)
- 选中 `.env.example` → 操作 → 重命名为 `.env`
- 双击 `.env` → 用 **Text Editor** 套件打开 (首次打开会提示安装)

### 3.2 必改项

```ini
MYSQL_ROOT_PASSWORD=你的强密码
SPRING_DATASOURCE_PASSWORD=你的强密码    # 必须与上同
```

> 暂时只局域网访问, 改不改都行; 暴露公网前必须改.

### 3.3 域白名单 (CORS, 公网访问时改)

> Spring CORS 因 `allowCredentials=true` **不支持通配符**, 必须逐个列出前端域名. 项目的 application.yml 默认值已含 93gushi.com/www.93gushi.com/home.93gushi.com + localhost, 公网部署时建议**用 .env 显式覆盖**, 把 localhost 条目删掉, 只留生产域名.

`.env` 里追加或取消注释:

```ini
ERP_CORS_ALLOWED_ORIGINS=https://93gushi.com,https://www.93gushi.com,https://home.93gushi.com
```

> 注意: 完整 Origin, 含协议, 不带路径/尾斜杠; 非默认端口必须写, 如 https://erp.93gushi.com:8443
> 多子域时多写几行, 用英文逗号分隔, 别加空格.

### 3.4 局域网开发 / 仅内网访问

如果不上公网、只局域网用 (例如 http://NAS_IP), 可以**不配** ERP_CORS_ALLOWED_ORIGINS, 让 application.yml 的默认值生效 (里面含 localhost:5173/8080 便于开发).

Vite 的 `server.allowedHosts` 已在 vite.config.js 里写好, 包含 `.93gushi.com` (一行通配所有子域) + `.local` (macOS 主机名) + 任意 IPv4 (Vite 内置放行), 局域网开发无需修改.

## 四、启动 (二选一)

### 方式 A: Container Manager 网页 (推荐)

1. **Container Manager** → 左侧 **项目** → **创建**
2. 项目名称: `erp-system`
3. 路径: `/volume1/docker/erp-system`
4. 来源: 选 `docker-compose.yml` (Compose V2)
5. 勾选 "构建项目", "启动后保持运行"
6. 点 **下一步** → **完成** → Container Manager 自动构建并启动

首次构建 5-15 分钟 (DS918+ J3455 单线程偏慢, 第二次会快很多, 镜像已缓存).

### 方式 B: SSH + docker compose

```bash
ssh admin@NAS_IP
sudo -i
cd /volume1/docker/erp-system
docker compose up -d --build
docker compose ps                    # 看运行状态
docker compose logs -f backend       # 实时看后端日志 (Ctrl+C 退出但容器继续跑)
```

## 五、访问

| 地址 | 说明 |
|---|---|
| `http://NAS_IP` | PC Web 前台 (推荐书签) |
| `http://NAS_IP:8080/api/doc.html` | Knife4j 接口文档 |
| `http://NAS_IP:8080/api/auth/captcha` | 验证码 (测后端健康) |
| 默认账号 | `admin` / `admin123` (首次登录后立即改) |

Container Manager → 容器 → 选中 `erp-backend` → 详情 → 看到 `Status: running` + `Healthy` 即就绪.

## 六、DSM 反向代理 + HTTPS (公网访问必须)

### 6.1 反向代理

**控制面板** → **登录门户** → **高级** → **反向代理** → **创建**:

| 字段 | 值 |
|---|---|
| 来源 | HTTPS, 443, 主机名 `erp.你的域名.com` |
| 目的地 | HTTP, 127.0.0.1, 80 |

> **必须**: HTTPS → HTTP, 不要直接 HTTPS → HTTPS, 内部就走明文 HTTP, 性能更好.

### 6.2 证书

**控制面板** → **安全性** → **证书** → **设置** → 给 `erp.你的域名.com` 申请 Let's Encrypt 证书, 勾选 "用作默认证书" 或 "用于反代".

### 6.3 防火墙

**控制面板** → **安全性** → **防火墙** → 放行:
- HTTPS 443 (从任何地方)

**不要** 直接放行 80 / 8080 / 3306 / 6379 到任何地方.

## 七、备份策略 (三层都做)

### 7.1 系统层: DSM 快照
**控制面板** → **共享文件夹** → 选中 `docker` → **创建快照**, 升级前 1 次.

### 7.2 异地备份: Hyper Backup
**Hyper Backup** → 创建备份任务 → 选 `docker` 共享文件夹 → 目标选 (外接 USB / 另一台 NAS / S3 兼容云). 每天凌晨 3 点自动备份.

### 7.3 应用层: ERP 自带
登录 ERP → **系统设置** → **数据备份** → 开启自动备份 (每天 1 次, 保留 30 天). 备份文件落在 NAS 卷 `./data/backup/`.

> 7.2 和 7.3 互不依赖, 任一单独失败另一层仍能恢复.

## 八、升级流程

```bash
ssh admin@NAS_IP
sudo -i
cd /volume1/docker/erp-system

# 1. 备份
docker exec erp-mysql mysqldump -uroot -p你的密码 industrial_erp > /volume1/docker/erp-system/db_backup_$(date +%Y%m%d).sql

# 2. 拉新代码
git pull origin main

# 3. 重新构建并滚动重启 (只重启有变更的容器, 数据卷不丢)
docker compose up -d --build

# 4. 如果有 SQL 变更, 按文件名顺序导入
docker exec -i erp-mysql mysql -uroot -p你的密码 industrial_erp < sql/新文件.sql
```

## 九、常见问题

### 9.1 启动后前端一直 loading
- 看后端日志: `docker compose logs -f backend`
- 最常见: `Communications link failure` — MySQL 还没就绪, 等待 30-60 秒, 后端 healthcheck 会自动重试
- 其次: `MYSQL_ROOT_PASSWORD` 和 `SPRING_DATASOURCE_PASSWORD` 不一致, 后端连不上库

### 9.2 MySQL OOM 被杀
- 看 Container Manager → 容器 → erp-mysql → 状态, 反复重启
- 调小 `innodb-buffer-pool-size` (默认 512M, 内存紧时改 384M)
- 或内存加到 16G

### 9.3 后端 8080 端口冲突
- NAS 自带 Web Station 用 80/443, 但 8080 一般没人占
- 看是谁占的: `sudo netstat -tlnp | grep 8080`
- 改 compose 的 `ports: - "8080:8080"` 第一段为其他端口, 例如 `"8888:8080"`

### 9.4 局域网访问不到
- Container Manager → 容器 → 选中 `erp-pc-web` → 详情 → 看 IP 是不是 NAS 主机 IP
- NAS 防火墙: **控制面板** → **安全性** → **防火墙** → 放行 80 (内部网络 → 任何)
- macOS 客户端: 浏览器允许弹窗的"接受传入连接"

### 9.5 想清空所有数据重新开始
```bash
docker compose down -v        # -v 会删数据卷, 慎用!
rm -rf data/                  # 清掉上传/备份
docker compose up -d --build
```

## 十、资源参考

- 项目部署总览: `docs/02_部署方案.md`
- Container Manager 官方文档: https://kb.synology.com/DSM/help/ContainerManager
- Hyper Backup: https://kb.synology.com/DSM/help/HyperBackup
