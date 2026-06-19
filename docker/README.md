# 工业ERP 群晖部署指南

## 版本
- 后端版本：v1.0.1
- 前端版本：pc-web (开发版)

---

## 方式一：在线部署（推荐网络畅通时使用）

### 前提条件
- 群晖已安装 Docker 套件
- 已上传 `industrial-erp-1.0.0.jar` 到 `docker` 同级目录

### 1. 上传文件到群晖

将以下文件上传到群晖同一个文件夹（如 `/volume1/docker/erp/`）：

```
erp/
├── industrial-erp-1.0.0.jar   ← 后端 JAR 包（从 backend/target/ 目录获取）
└── docker/
    ├── docker-compose.yml
    └── README.md
```

### 2. SSH 连接群晖

```bash
ssh admin@192.168.1.100   # 替换为你的群晖IP
```

### 3. 配置 Docker 镜像加速（可选，国内网络需要）

如果拉取 Docker Hub 镜像超时，配置国内镜像源：

```bash
sudo mkdir -p /etc/docker
sudo tee /etc/docker/daemon.json <<'EOF'
{
  "registry-mirrors": [
    "https://docker.1ms.run",
    "https://docker.m.daocloud.io"
  ]
}
EOF
sudo synoservicecfg --restart docker
```

### 4. 构建并启动

```bash
cd /volume1/docker/erp/docker
docker-compose up -d
```

### 5. 验证

```bash
curl http://localhost:8080/api/auth/me
# 返回 {"code":401} 表示启动成功
```

### 6. 查看日志

```bash
docker logs -f industrial-erp
```

---

## 方式二：离线部署（群晖无法访问 Docker Hub 时使用）

### 步骤 1：在本地 Mac 上拉取并导出镜像

本地 Mac 需要安装 [Docker Desktop](https://www.docker.com/products/docker-desktop/)，安装完成后执行：

```bash
# 拉取 Java 17 运行时镜像
docker pull openjdk:17-jre-slim

# 导出为 tar 文件
docker save openjdk:17-jre-slim -o ~/Desktop/openjdk17.tar
```

### 步骤 2：上传到群晖

```bash
scp ~/Desktop/openjdk17.tar admin@192.168.1.100:/volume1/docker/erp/
```

### 步骤 3：群晖上加载镜像

```bash
ssh admin@192.168.1.100
cd /volume1/docker/erp
docker load -i openjdk17.tar
```

### 步骤 4：启动服务

```bash
cd /volume1/docker/erp/docker
docker-compose up -d
```

---

## 访问地址

- **后端API**: `http://<群晖IP>:8080/api`
- **前端PC**: 将 `pc-web` 打包后部署到 Nginx，Nginx 反向代理到 `:8080`

## 停止服务

```bash
cd /volume1/docker/erp/docker
docker-compose down
```

## 开机自启

`restart: always` 已配置在 docker-compose.yml 中，Docker 服务启动后容器会自动运行。

如果群晖重启后容器没自动启动，需确保 Docker 套件设置为「开机自启」：
> 控制面板 > 任务计划 > 触发任务 > Docker: 开机自启动
