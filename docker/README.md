# 工业ERP 群晖部署指南

## 前提条件
- 群晖已安装 Docker 套件
- 已上传 `industrial-erp-1.0.0.jar` 到 `docker` 同级目录

## 部署步骤

### 1. 上传文件到群晖

将以下文件上传到群晖同一个文件夹（如 `/volume1/docker/erp/`）：

```
erp/
├── industrial-erp-1.0.0.jar   ← 后端 JAR 包
└── docker/
    ├── docker-compose.yml
    └── README.md
```

### 2. SSH 连接群晖

```bash
ssh admin@192.168.1.100   # 替换为你的群晖IP
```

### 3. 构建并启动

```bash
cd /volume1/docker/erp/docker
docker-compose up -d
```

### 4. 验证

```bash
curl http://localhost:8080/api/auth/me
# 返回 {"code":401} 表示启动成功
```

### 5. 查看日志

```bash
docker logs -f industrial-erp
```

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
