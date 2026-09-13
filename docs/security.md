# v1.1.49: 安全配置文档

## .env 必填项与生成命令

启动前 **必须** 设置以下变量, 留空或用占位值将导致 backend 启动失败 (`SecurityPreflightValidator`):

| 变量 | 生成命令 | 强度要求 | 作用 |
|---|---|---|---|
| `MYSQL_ROOT_PASSWORD` | `openssl rand -base64 24` | ≥16 字符, 含大小写 + 数字 | MySQL root 密码, 同时影响 `SPRING_DATASOURCE_PASSWORD` |
| `SA_TOKEN_JWT_SECRET_KEY` | `openssl rand -hex 32` | ≥32 字符, 不在黑名单 | JWT 签名密钥, 用于鉴权 |
| `FEIE_DEFAULT_USER` | 飞鹅云后台查 | - | 飞鹅云打印默认账号 |

## 已知弱密钥黑名单 (v1.1.49 起拒绝启动)

- `industrial-erp-jwt-test-p2-fix`
- `erp_jwt_secret_2026_gpssong_xxxx`
- `changeme` / `change-me` / `secret` / `default` / `test` / `12345678` / `password`

## 升级流程 (.env 改动后)

```bash
# 1. 备份当前 .env (时间戳后缀)
cp .env .env.bak.$(date +%Y%m%d_%H%M%S)

# 2. 用编辑器改 .env, 确认变更
diff .env .env.bak.<上次>

# 3. 应用新环境变量 (backend 重启生效)
docker compose restart backend

# 4. 验证启动日志
docker compose logs --tail=50 backend
```

## 密钥泄漏后果

| 泄漏项 | 后果 |
|---|---|
| `SA_TOKEN_JWT_SECRET_KEY` | 攻击者可伪造 token 越权访问 API |
| `MYSQL_ROOT_PASSWORD` | 攻击者可直连 MySQL 导出/篡改数据 |

## 安全加固建议 (P2 未实现)

- [ ] 启用 HTTPS (NAS DSM 反向代理已支持, 但容器内 nginx 仍是 HTTP)
- [ ] 把 JWT secret / MySQL 密码放入 Docker secrets (`docker stack deploy`)
- [ ] 定期轮换密钥 (每次部署换一次, 不保留 .env.bak > 30 天)
- [ ] 生产 .env 加入 `.gitignore` 时同时加 `*.bak.*` 防止泄漏
