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

### 8.1 App H5 单独升级 (uni-app 编译产物)

App 端 H5 资源 (`/usr/share/nginx/html` 在 `erp-app-h5` 容器内) 不能用 bind mount, 必须重建镜像。

```bash
# === Mac 端 ===
cd /path/to/erp-system/app
npm run build:h5  # 输出到 dist/build/h5/
cd dist/build/h5
tar czf /tmp/app-h5-build.tar.gz .  # 273KB 左右

# 起 HTTP server 给 NAS 拉 (避免 scp subsystem 限制)
python3 -m http.server 18888 --bind 0.0.0.0 &

# === NAS 端 (ssh) ===
# 1. 拉 tar
curl -s -o ~/app-h5-build.tar.gz http://<mac_ip>:18888/app-h5-build.tar.gz

# 2. ⚠️ 必须在 user home 解压 (ACL 锁 dist/build/h5, 直接解压会丢 assets/)
mkdir -p ~/test-extract && cd ~/test-extract
tar xzf ~/app-h5-build.tar.gz

# 3. 复制到 dist (cp 不触发 ACL 拦截)
rm -rf /volume1/docker/erp-system/app/dist/build/h5
cp -r ~/test-extract /volume1/docker/erp-system/app/dist/build/h5

# 4. Dockerfile 必须放 NAS 上 (没的话 scp 上去)
#    FROM nginx:1.27-alpine  ← 不能锁 sha256 (拉镜像超时)
#    COPY dist/build/h5 /usr/share/nginx/html

# 5. 重建镜像 + 重启
cd /volume1/docker/erp-system
sudo docker build --no-cache -t erp-app-h5:latest ./app
sudo docker rm -f erp-app-h5
sudo docker run -d --name erp-app-h5 --restart unless-stopped \
  --network erp-system_erp-net -p 18090:80 erp-app-h5:latest

# 6. 验证 (chunk hash 应该是新的)
curl -s http://<nas_ip>:18090/ | grep -oE 'index-[A-Za-z0-9_-]*\.js'
```

**踩坑** (v1.1.45):
- `dist/build/h5` 是 Synology ACL 锁定的, tar 直接 `-C` 解压会丢失 `assets/` 子目录 → 容器 nginx 找不到 JS → 整个 H5 空白
- mac tar 含 `LIBARCHIVE.xattr.com.apple.provenance` xattr, NAS tar 会告警但能正常解 (忽略 `tar: Ignoring unknown extended header keyword`)
- `FROM nginx:1.27-alpine@sha256:...` 在 NAS 上 `docker build` 会卡住拉镜像, 必须去掉 sha256 锁

### 8.2 后端 jar 单独升级 (修改 XML/Java)

`backend/industrial-erp-1.0.4.jar` 在 NAS 上**有两份** (根目录 + `backend/` 子目录), 但 Dockerfile `COPY industrial-erp-*.jar` 是从 build context `./backend/` 复制, **必须改 `backend/industrial-erp-1.0.4.jar`**。

```bash
# === Mac 端 ===
cd /path/to/erp-system
# 假设改了两个 XML: ReportMapper.xml + InvLedgerQueryMapper.xml
python3 -c "
import zipfile, shutil
JAR = './industrial-erp-1.0.4.jar'
REPL = {
    'BOOT-INF/classes/mapper/report/ReportMapper.xml': open('./backend/src/main/resources/mapper/report/ReportMapper.xml', 'rb').read(),
    'BOOT-INF/classes/mapper/inventory/InvLedgerQueryMapper.xml': open('./backend/src/main/resources/mapper/inventory/InvLedgerQueryMapper.xml', 'rb').read(),
}
with zipfile.ZipFile(JAR) as zin, zipfile.ZipFile(JAR+'.tmp', 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        zout.writestr(item, REPL.get(item.filename, zin.read(item.filename)))
shutil.move(JAR+'.tmp', JAR)
"

# 起 HTTP server
python3 -m http.server 18888 --bind 0.0.0.0 &

# === NAS 端 ===
curl -s -o /volume1/docker/erp-system/backend/industrial-erp-1.0.4.jar http://<mac_ip>:18888/industrial-erp-1.0.4.jar

# ⚠️ 验证 size (管道传大文件偶尔会变 0 bytes)
ls -la /volume1/docker/erp-system/backend/industrial-erp-1.0.4.jar
# 期望: 约 100MB (100597929 bytes), 不是 0

# 重建镜像 (--no-cache 强制让 COPY 层重新拷贝)
cd /volume1/docker/erp-system
sudo docker build --no-cache -t erp-system-backend:latest ./backend

# 重启容器 (手动 docker run, 不用 compose)
sudo docker rm -f erp-backend
sudo docker run -d --name erp-backend --restart unless-stopped \
  --network erp-system_erp-net -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod -e TZ=Asia/Shanghai \
  -e MYSQL_ROOT_PASSWORD=<你的密码> \
  -e SA_TOKEN_JWT_SECRET_KEY=<你的 jwt 密钥> \
  -e SPRING_DATASOURCE_URL="jdbc:mysql://erp-mysql:3306/industrial_erp?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" \
  -e SPRING_DATASOURCE_USERNAME=root -e SPRING_DATASOURCE_PASSWORD=<你的密码> \
  -e SPRING_DATA_REDIS_HOST=erp-redis -e SPRING_DATA_REDIS_PORT=6379 \
  -e JAVA_OPTS="-Xms256m -Xmx768m -XX:MaxMetaspaceSize=192m -XX:+UseG1GC -Dfile.encoding=UTF-8 -Duser.timezone=GMT+8" \
  -e ERP_UPLOAD_PATH=/opt/industrial-erp/upload -e ERP_BACKUP_PATH=/opt/industrial-erp/backup \
  -v /volume1/docker/erp-system/data/upload:/opt/industrial-erp/upload \
  -v /volume1/docker/erp-system/data/backup:/opt/industrial-erp/backup \
  erp-system-backend:latest

# 验证 jar 是新的
sudo docker exec erp-backend sh -c "stat /opt/app/app.jar"
# 期望: size=新 size, Modify=刚刚替换时间
```

**注意**:
- `docker compose up` 在 NAS 上会因 yaml line 84 `${VAR:?msg}` 报 "mapping values are not allowed", 必须用 `docker run` 手动起。
- `SA_TOKEN_JWT_SECRET_KEY` 必须从 `.env` 读, 后端启动时强制校验, 无默认值。
- Sa-Token 用 `Authorization: <token>` header, **不是** `satoken: <token>`。

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

## 九、常见问题

### 9.5 改了 pc-web 容器端口后 8088 报 502

DSM 的 Container Manager 会自动生成反向代理配置 `/etc/nginx/conf.d/http.erp8088.conf`, 把 `home.93gushi.com:8088/` 反代到 `http://127.0.0.1:18080` (pc-web 容器).

如果你把 `docker-compose.yml` 中 `pc-web` 的端口改成 `8180:80`, 那么 DSM 反代仍然指向 18080, 上游断了 → 502 Bad Gateway.

**修复**: 改端口后必须同步修改 DSM 反代配置:
1. DSM 控制面板 → 登录门户 → 高级 → 编辑 `http.erp8088.conf`
2. 把 `proxy_pass http://127.0.0.1:18080` 改成对应端口
3. 或者经 DSM 控制面板 → Web 应用程序 → 重建反向代理规则

**推荐**: 固定使用 `18080:80`, 避免 DSM 反代冲突.

### 9.6 端口冲突

- 群晖 DSM Web Station 占用 **80 端口**, 所以 pc-web 容器映射 `18080:80` (而非 80:80)
- DSM 反代统一走 **8088 端口**, 由 DSM 自动生成的 nginx 配置处理
- 外网访问: `home.93gushi.com:8088` → 反代到 18080 (pc-web) / 8080 (backend)

## 十、资源参考

- 项目部署总览: `docs/02_部署方案.md`
- Container Manager 官方文档: https://kb.synology.com/DSM/help/ContainerManager
- Hyper Backup: https://kb.synology.com/DSM/help/HyperBackup
